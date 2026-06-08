/*
 *  Copyright 2004 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#ifndef RTC_BASE_SOCKET_ADAPTERS_H_
#define RTC_BASE_SOCKET_ADAPTERS_H_

#include <memory>
#include <string>

#include "absl/strings/string_view.h"
#include "api/array_view.h"
#include "rtc_base/async_packet_socket.h"
#include "rtc_base/async_socket.h"
#include "rtc_base/crypt_string.h"
#include "rtc_base/network/received_packet.h"
#include "rtc_base/socket_factory.h"

namespace rtc {

class AsyncUDPSocket;
struct HttpAuthContext;
class ByteBufferReader;
class ByteBufferWriter;

///////////////////////////////////////////////////////////////////////////////

// Implements a socket adapter that can buffer and process data internally,
// as in the case of connecting to a proxy, where you must speak the proxy
// protocol before commencing normal socket behavior.
class BufferedReadAdapter : public AsyncSocketAdapter {
 public:
  BufferedReadAdapter(Socket* socket, size_t buffer_size);
  ~BufferedReadAdapter() override;

  BufferedReadAdapter(const BufferedReadAdapter&) = delete;
  BufferedReadAdapter& operator=(const BufferedReadAdapter&) = delete;

  int Send(const void* pv, size_t cb) override;
  int Recv(void* pv, size_t cb, int64_t* timestamp) override;

 protected:
  int DirectSend(const void* pv, size_t cb) {
    return AsyncSocketAdapter::Send(pv, cb);
  }

  void BufferInput(bool on = true);
  virtual void ProcessInput(char* data, size_t* len) = 0;

  void OnReadEvent(Socket* socket) override;

 private:
  char* buffer_;
  size_t buffer_size_, data_len_;
  bool buffering_;
};

///////////////////////////////////////////////////////////////////////////////

// Implements a socket adapter that performs the client side of a
// fake SSL handshake. Used for "ssltcp" P2P functionality.
class AsyncSSLSocket : public BufferedReadAdapter {
 public:
  static ArrayView<const uint8_t> SslClientHello();
  static ArrayView<const uint8_t> SslServerHello();

  explicit AsyncSSLSocket(Socket* socket);

  AsyncSSLSocket(const AsyncSSLSocket&) = delete;
  AsyncSSLSocket& operator=(const AsyncSSLSocket&) = delete;

  int Connect(const SocketAddress& addr) override;

 protected:
  void OnConnectEvent(Socket* socket) override;
  void ProcessInput(char* data, size_t* len) override;
};

///////////////////////////////////////////////////////////////////////////////

// Implements a socket adapter that speaks the HTTP/S proxy protocol.
class AsyncHttpsProxySocket : public BufferedReadAdapter {
 public:
  AsyncHttpsProxySocket(Socket* socket,
                        absl::string_view user_agent,
                        const SocketAddress& proxy,
                        absl::string_view username,
                        const CryptString& password);
  ~AsyncHttpsProxySocket() override;

  AsyncHttpsProxySocket(const AsyncHttpsProxySocket&) = delete;
  AsyncHttpsProxySocket& operator=(const AsyncHttpsProxySocket&) = delete;

  // If connect is forced, the adapter will always issue an HTTP CONNECT to the
  // target address.  Otherwise, it will connect only if the destination port
  // is not port 80.
  void SetForceConnect(bool force) { force_connect_ = force; }

  int Connect(const SocketAddress& addr) override;
  SocketAddress GetRemoteAddress() const override;
  int Close() override;
  ConnState GetState() const override;

 protected:
  void OnConnectEvent(Socket* socket) override;
  void OnCloseEvent(Socket* socket, int err) override;
  void ProcessInput(char* data, size_t* len) override;

  bool ShouldIssueConnect() const;
  void SendRequest();
  void ProcessLine(char* data, size_t len);
  void EndResponse();
  void Error(int error);

 private:
  SocketAddress proxy_, dest_;
  std::string agent_, user_, headers_;
  CryptString pass_;
  bool force_connect_;
  size_t content_length_;
  int defer_error_;
  bool expect_close_;
  enum ProxyState {
    PS_INIT,
    PS_LEADER,
    PS_AUTHENTICATE,
    PS_SKIP_HEADERS,
    PS_ERROR_HEADERS,
    PS_TUNNEL_HEADERS,
    PS_SKIP_BODY,
    PS_TUNNEL,
    PS_WAIT_CLOSE,
    PS_ERROR
  } state_;
  HttpAuthContext* context_;
  std::string unknown_mechanisms_;
};

///////////////////////////////////////////////////////////////////////////////

// SOCKS5 UDP ASSOCIATE client wrapper.
//
// Bridges WebRTC's AsyncPacketSocket API to a SOCKS5 proxy that supports
// UDP relaying (RFC 1928 §7). Intended for routing TURN/STUN/reflector UDP
// traffic through xray's SOCKS5 inbound with `"udp": true`.
//
// Lifetime:
//   1. Opens a TCP control connection to the SOCKS5 server.
//   2. Performs no-auth handshake (`05 01 00` → `05 00`).
//   3. Sends UDP ASSOCIATE (`05 03 00 01 0.0.0.0:0`). Server replies with
//      the UDP relay endpoint (BND.ADDR:BND.PORT).
//   4. Relay is ready: SendTo() wraps each datagram with a SOCKS5 UDP
//      header and sends it to the relay. Incoming datagrams from the relay
//      have their header stripped and are surfaced with the true source
//      address.
//   5. If the control channel closes, the UDP relay is invalidated.
//
// All traffic sent before the relay is ready is dropped (EWOULDBLOCK).
class AsyncSocksProxyUdpSocket : public AsyncPacketSocket {
 public:
  AsyncSocksProxyUdpSocket(SocketFactory* socket_factory,
                           const SocketAddress& local_bind,
                           const SocketAddress& socks_server);
  ~AsyncSocksProxyUdpSocket() override;

  AsyncSocksProxyUdpSocket(const AsyncSocksProxyUdpSocket&) = delete;
  AsyncSocksProxyUdpSocket& operator=(const AsyncSocksProxyUdpSocket&) = delete;

  // True if the local UDP socket was bound successfully. If this returns
  // false, the object should be deleted immediately by the caller.
  bool IsBound() const;

  SocketAddress GetLocalAddress() const override;
  SocketAddress GetRemoteAddress() const override;
  int Send(const void* pv, size_t cb, const PacketOptions& options) override;
  int SendTo(const void* pv,
             size_t cb,
             const SocketAddress& addr,
             const PacketOptions& options) override;
  int Close() override;
  State GetState() const override;
  int GetOption(Socket::Option opt, int* value) override;
  int SetOption(Socket::Option opt, int value) override;
  int GetError() const override;
  void SetError(int error) override;

 private:
  enum Stage {
    ST_INIT,
    ST_HELLO,
    ST_ASSOCIATE,
    ST_READY,
    ST_ERROR,
  };

  void OnControlConnect(Socket* s);
  void OnControlRead(Socket* s);
  void OnControlClose(Socket* s, int err);
  void OnUdpPacket(AsyncPacketSocket* s, const ReceivedPacket& packet);
  void SendGreeting();
  void SendAssociate();
  bool HandleHelloReply();
  bool HandleAssociateReply();
  void Fail(int error);

  std::unique_ptr<Socket> control_;
  std::unique_ptr<AsyncUDPSocket> udp_;
  SocketAddress socks_server_;
  SocketAddress udp_relay_;
  uint8_t ctrl_buf_[512];
  size_t ctrl_len_ = 0;
  Stage stage_ = ST_INIT;
  int error_ = 0;
};

}  // namespace rtc

#endif  // RTC_BASE_SOCKET_ADAPTERS_H_
