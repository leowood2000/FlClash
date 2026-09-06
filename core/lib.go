//go:build cgo

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"core/platform"
	t "core/tun"
	"encoding/json"
	"errors"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"golang.org/x/sync/semaphore"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

var eventListener unsafe.Pointer

type TunHandler struct {
	listener *sing_tun.Listener
	callback unsafe.Pointer

	limit *semaphore.Weighted
}

func (th *TunHandler) start(fd int, stack, address, dns string) {
	runLock.Lock()
	defer runLock.Unlock()
	_ = th.limit.Acquire(context.TODO(), 4)
	defer th.limit.Release(4)
	th.initHook()
	tunListener := t.Start(fd, stack, address, dns)
	if tunListener != nil {
		log.Infoln("TUN address: %v", tunListener.Address())
		th.listener = tunListener
		return
	}
	th.clear()
}

func (th *TunHandler) close() {
	_ = th.limit.Acquire(context.TODO(), 4)
	defer th.limit.Release(4)
	th.clear()
}

func (th *TunHandler) clear() {
	th.removeHook()
	if th.listener != nil {
		_ = th.listener.Close()
	}
	if th.callback != nil {
		releaseObject(th.callback)
	}
	th.callback = nil
	th.listener = nil
}

func (th *TunHandler) handleProtect(fd int) {
	_ = th.limit.Acquire(context.Background(), 1)
	defer th.limit.Release(1)

	if th.listener == nil {
		return
	}

	protect(th.callback, fd)
}

func (th *TunHandler) handleResolveProcess(source, target net.Addr) string {
	_ = th.limit.Acquire(context.Background(), 1)
	defer th.limit.Release(1)

	if th.listener == nil {
		return ""
	}
	var protocol int
	uid := -1
	switch source.Network() {
	case "udp", "udp4", "udp6":
		protocol = syscall.IPPROTO_UDP
	case "tcp", "tcp4", "tcp6":
		protocol = syscall.IPPROTO_TCP
	}
	if version < 29 {
		uid = platform.QuerySocketUidFromProcFs(source, target)
	}
	return resolveProcess(th.callback, protocol, source.String(), target.String(), uid)
}

func (th *TunHandler) initHook() {
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		if platform.ShouldBlockConnection() {
			return errBlocked
		}
		switch network {
		case "tcp4":
			atomic.AddUint64(&protectTCP4, 1)
		case "tcp6":
			atomic.AddUint64(&protectTCP6, 1)
		case "udp4":
			atomic.AddUint64(&protectUDP4, 1)
		case "udp6":
			atomic.AddUint64(&protectUDP6, 1)
		}
		return conn.Control(func(fd uintptr) {
			tunHandler.handleProtect(int(fd))
		})
	}
	process.DefaultPackageNameResolver = func(metadata *constant.Metadata) (string, error) {
		src, dst := metadata.RawSrcAddr, metadata.RawDstAddr
		if src == nil || dst == nil {
			return "", process.ErrInvalidNetwork
		}
		return tunHandler.handleResolveProcess(src, dst), nil
	}
}

func (th *TunHandler) removeHook() {
	dialer.DefaultSocketHook = nil
	process.DefaultPackageNameResolver = nil
}

var (
	tunLock    sync.Mutex
	errBlocked = errors.New("blocked")
	tunHandler *TunHandler

	protectTCP4 uint64
	protectTCP6 uint64
	protectUDP4 uint64
	protectUDP6 uint64

	protectLogStop chan struct{}
)

func handleStopTun() {
	tunLock.Lock()
	defer tunLock.Unlock()
	if protectLogStop != nil {
		close(protectLogStop)
		protectLogStop = nil
	}
	// Reset protect counters
	atomic.StoreUint64(&protectTCP4, 0)
	atomic.StoreUint64(&protectTCP6, 0)
	atomic.StoreUint64(&protectUDP4, 0)
	atomic.StoreUint64(&protectUDP6, 0)
	if tunHandler != nil {
		tunHandler.close()
	}
}

func handleStartTun(callback unsafe.Pointer, fd int, stack, address, dns string) {
	handleStopTun()
	tunLock.Lock()
	defer tunLock.Unlock()
	if fd != 0 {
		tunHandler = &TunHandler{
			callback: callback,
			limit:    semaphore.NewWeighted(4),
		}
		tunHandler.start(fd, stack, address, dns)

		// Start periodic protect counter logging
		protectLogStop = make(chan struct{})
		go func(stop <-chan struct{}) {
			ticker := time.NewTicker(10 * time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-stop:
					return
				case <-ticker.C:
					log.Infoln("[TUN] protect-stats: tcp4=%d tcp6=%d udp4=%d udp6=%d",
						atomic.LoadUint64(&protectTCP4), atomic.LoadUint64(&protectTCP6),
						atomic.LoadUint64(&protectUDP4), atomic.LoadUint64(&protectUDP6))
				}
			}
		}(protectLogStop)
	}
}

func handleUpdateDns(value string) {
	go func() {
		log.Infoln("[DNS] updateDns %s", value)
		dns.UpdateSystemDNS(strings.Split(value, ","))
		dns.FlushCacheWithDefaultResolver()
	}()
}

func (result ActionResult) send() {
	data, err := result.Json()
	if err != nil {
		return
	}
	invokeResult(result.callback, string(data))
	if result.Method != messageMethod {
		releaseObject(result.callback)
	}
}

func nextHandle(action *Action, result ActionResult) bool {
	switch action.Method {
	case updateDnsMethod:
		data := action.Data.(string)
		handleUpdateDns(data)
		result.success(true)
		return true
	}
	return false
}

//export invokeAction
func invokeAction(callback unsafe.Pointer, paramsChar *C.char) {
	params := takeCString(paramsChar)
	var action = &Action{}
	err := json.Unmarshal([]byte(params), action)
	if err != nil {
		invokeResult(callback, err.Error())
		return
	}
	result := ActionResult{
		Id:       action.Id,
		Method:   action.Method,
		callback: callback,
	}
	go handleAction(action, result)
}

//export startTUN
func startTUN(callback unsafe.Pointer, fd C.int, stackChar, addressChar, dnsChar *C.char) bool {
	handleStartTun(callback, int(fd), takeCString(stackChar), takeCString(addressChar), takeCString(dnsChar))
	if !isRunning {
		handleStartListener()
	} else {
		handleResetConnections()
	}
	return true
}

//export quickSetup
func quickSetup(callback unsafe.Pointer, initParamsChar *C.char, setupParamsChar *C.char) {
	go func() {
		initParamsString := takeCString(initParamsChar)
		setupParamsString := takeCString(setupParamsChar)
		if !handleInitClash(initParamsString) {
			invokeResult(callback, "init failed")
			return
		}
		isRunning = true
		message := handleSetupConfig([]byte(setupParamsString))
		invokeResult(callback, message)
	}()
}

//export setEventListener
func setEventListener(listener unsafe.Pointer) {
	if eventListener != nil || listener == nil {
		releaseObject(eventListener)
	}
	eventListener = listener
}

//export getTotalTraffic
func getTotalTraffic(onlyStatisticsProxy bool) *C.char {
	data := C.CString(handleGetTotalTraffic(onlyStatisticsProxy))
	defer C.free(unsafe.Pointer(data))
	return data
}

//export getTraffic
func getTraffic(onlyStatisticsProxy bool) *C.char {
	data := C.CString(handleGetTraffic(onlyStatisticsProxy))
	defer C.free(unsafe.Pointer(data))
	return data
}

func sendMessage(message Message) {
	if eventListener == nil {
		return
	}
	result := ActionResult{
		Method:   messageMethod,
		callback: eventListener,
		Data:     message,
	}
	result.send()
}

//export stopTun
func stopTun() {
	handleStopTun()
	if isRunning {
		handleStopListener()
	}
}

//export suspend
func suspend(suspended bool) {
	handleSuspend(suspended)
}

//export forceGC
func forceGC() {
	handleForceGC()
}

//export updateDns
func updateDns(s *C.char) {
	handleUpdateDns(takeCString(s))
}
