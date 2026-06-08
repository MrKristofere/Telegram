/*
 *  Copyright 2004 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#if defined(_MSC_VER) && _MSC_VER < 1300
#pragma warning(disable : 4786)
#endif

#include "rtc_base/socket_adapters.h"

#include <algorithm>

#include "absl/strings/match.h"
#include "absl/strings/string_view.h"
#include "rtc_base/async_udp_socket.h"
#include "rtc_base/buffer.h"
#include "rtc_base/byte_buffer.h"
#include "rtc_base/byte_order.h"
#include "rtc_base/checks.h"
#include "rtc_base/http_common.h"
#include "rtc_base/ip_address.h"
#include "rtc_base/logging.h"
#include "rtc_base/strings/string_builder.h"
#include "rtc_base/zero_memory.h"

namespace rtc {

BufferedReadAdapter::BufferedReadAdapter(Socket* socket, size_t size)
    : AsyncSocketAdapter(socket),
      buffer_size_(size),
      data_len_(0),
      buffering_(false) {
  buffer_ = new char[buffer_size_];
}

BufferedReadAdapter::~BufferedReadAdapter() {
  delete[] buffer_;
}

int BufferedReadAdapter::Send(const void* pv, size_t cb) {
  if (buffering_) {
    // TODO: Spoof error better; Signal Writeable
    SetError(EWOULDBLOCK);
    return -1;
  }
  return AsyncSocketAdapter::Send(pv, cb);
}

int BufferedReadAdapter::Recv(void* pv, size_t cb, int64_t* timestamp) {
  if (buffering_) {
    SetError(EWOULDBLOCK);
    return -1;
  }

  size_t read = 0;

  if (data_len_) {
    read = std::min(cb, data_len_);
    memcpy(pv, buffer_, read);
    data_len_ -= read;
    if (data_len_ > 0) {
      memmove(buffer_, buffer_ + read, data_len_);
    }
    pv = static_cast<char*>(pv) + read;
    cb -= read;
  }

  // FIX: If cb == 0, we won't generate another read event

  int res = AsyncSocketAdapter::Recv(pv, cb, timestamp);
  if (res >= 0) {
    // Read from socket and possibly buffer; return combined length
    return res + static_cast<int>(read);
  }

  if (read > 0) {
    // Failed to read from socket, but still read something from buffer
    return static_cast<int>(read);
  }

  // Didn't read anything; return error from socket
  return res;
}

void BufferedReadAdapter::BufferInput(bool on) {
  buffering_ = on;
}

void BufferedReadAdapter::OnReadEvent(Socket* socket) {
  RTC_DCHECK(socket == GetSocket());

  if (!buffering_) {
    AsyncSocketAdapter::OnReadEvent(socket);
    return;
  }

  if (data_len_ >= buffer_size_) {
    RTC_LOG(LS_ERROR) << "Input buffer overflow";
    RTC_DCHECK_NOTREACHED();
    data_len_ = 0;
  }

  int len = AsyncSocketAdapter::Recv(buffer_ + data_len_,
                                     buffer_size_ - data_len_, nullptr);
  if (len < 0) {
    // TODO: Do something better like forwarding the error to the user.
    RTC_LOG_ERR(LS_INFO) << "Recv";
    return;
  }

  data_len_ += len;

  ProcessInput(buffer_, &data_len_);
}

///////////////////////////////////////////////////////////////////////////////

// This is a SSL v2 CLIENT_HELLO message.
// TODO: Should this have a session id? The response doesn't have a
// certificate, so the hello should have a session id.
static const uint8_t kSslClientHello[] = {
    0x80, 0x46,                                            // msg len
    0x01,                                                  // CLIENT_HELLO
    0x03, 0x01,                                            // SSL 3.1
    0x00, 0x2d,                                            // ciphersuite len
    0x00, 0x00,                                            // session id len
    0x00, 0x10,                                            // challenge len
    0x01, 0x00, 0x80, 0x03, 0x00, 0x80, 0x07, 0x00, 0xc0,  // ciphersuites
    0x06, 0x00, 0x40, 0x02, 0x00, 0x80, 0x04, 0x00, 0x80,  //
    0x00, 0x00, 0x04, 0x00, 0xfe, 0xff, 0x00, 0x00, 0x0a,  //
    0x00, 0xfe, 0xfe, 0x00, 0x00, 0x09, 0x00, 0x00, 0x64,  //
    0x00, 0x00, 0x62, 0x00, 0x00, 0x03, 0x00, 0x00, 0x06,  //
    0x1f, 0x17, 0x0c, 0xa6, 0x2f, 0x00, 0x78, 0xfc,        // challenge
    0x46, 0x55, 0x2e, 0xb1, 0x83, 0x39, 0xf1, 0xea         //
};

// static
ArrayView<const uint8_t> AsyncSSLSocket::SslClientHello() {
  // Implicit conversion directly from kSslClientHello to ArrayView fails when
  // built with gcc.
  return {kSslClientHello, sizeof(kSslClientHello)};
}

// This is a TLSv1 SERVER_HELLO message.
static const uint8_t kSslServerHello[] = {
    0x16,                                            // handshake message
    0x03, 0x01,                                      // SSL 3.1
    0x00, 0x4a,                                      // message len
    0x02,                                            // SERVER_HELLO
    0x00, 0x00, 0x46,                                // handshake len
    0x03, 0x01,                                      // SSL 3.1
    0x42, 0x85, 0x45, 0xa7, 0x27, 0xa9, 0x5d, 0xa0,  // server random
    0xb3, 0xc5, 0xe7, 0x53, 0xda, 0x48, 0x2b, 0x3f,  //
    0xc6, 0x5a, 0xca, 0x89, 0xc1, 0x58, 0x52, 0xa1,  //
    0x78, 0x3c, 0x5b, 0x17, 0x46, 0x00, 0x85, 0x3f,  //
    0x20,                                            // session id len
    0x0e, 0xd3, 0x06, 0x72, 0x5b, 0x5b, 0x1b, 0x5f,  // session id
    0x15, 0xac, 0x13, 0xf9, 0x88, 0x53, 0x9d, 0x9b,  //
    0xe8, 0x3d, 0x7b, 0x0c, 0x30, 0x32, 0x6e, 0x38,  //
    0x4d, 0xa2, 0x75, 0x57, 0x41, 0x6c, 0x34, 0x5c,  //
    0x00, 0x04,                                      // RSA/RC4-128/MD5
    0x00                                             // null compression
};

// static
ArrayView<const uint8_t> AsyncSSLSocket::SslServerHello() {
  return {kSslServerHello, sizeof(kSslServerHello)};
}

AsyncSSLSocket::AsyncSSLSocket(Socket* socket)
    : BufferedReadAdapter(socket, 1024) {}

int AsyncSSLSocket::Connect(const SocketAddress& addr) {
  // Begin buffering before we connect, so that there isn't a race condition
  // between potential senders and receiving the OnConnectEvent signal
  BufferInput(true);
  return BufferedReadAdapter::Connect(addr);
}

void AsyncSSLSocket::OnConnectEvent(Socket* socket) {
  RTC_DCHECK(socket == GetSocket());
  // TODO: we could buffer output too...
  const int res = DirectSend(kSslClientHello, sizeof(kSslClientHello));
  if (res != sizeof(kSslClientHello)) {
    RTC_LOG(LS_ERROR) << "Sending fake SSL ClientHello message failed.";
    Close();
    SignalCloseEvent(this, 0);
  }
}

void AsyncSSLSocket::ProcessInput(char* data, size_t* len) {
  if (*len < sizeof(kSslServerHello))
    return;

  if (memcmp(kSslServerHello, data, sizeof(kSslServerHello)) != 0) {
    RTC_LOG(LS_ERROR) << "Received non-matching fake SSL ServerHello message.";
    Close();
    SignalCloseEvent(this, 0);  // TODO: error code?
    return;
  }

  *len -= sizeof(kSslServerHello);
  if (*len > 0) {
    memmove(data, data + sizeof(kSslServerHello), *len);
  }

  bool remainder = (*len > 0);
  BufferInput(false);
  SignalConnectEvent(this);

  // FIX: if SignalConnect causes the socket to be destroyed, we are in trouble
  if (remainder)
    SignalReadEvent(this);
}

///////////////////////////////////////////////////////////////////////////////

AsyncHttpsProxySocket::AsyncHttpsProxySocket(Socket* socket,
                                             absl::string_view user_agent,
                                             const SocketAddress& proxy,
                                             absl::string_view username,
                                             const CryptString& password)
    : BufferedReadAdapter(socket, 1024),
      proxy_(proxy),
      agent_(user_agent),
      user_(username),
      pass_(password),
      force_connect_(false),
      state_(PS_ERROR),
      context_(0) {}

AsyncHttpsProxySocket::~AsyncHttpsProxySocket() {
  delete context_;
}

int AsyncHttpsProxySocket::Connect(const SocketAddress& addr) {
  int ret;
  RTC_LOG(LS_VERBOSE) << "AsyncHttpsProxySocket::Connect("
                      << proxy_.ToSensitiveString() << ")";
  dest_ = addr;
  state_ = PS_INIT;
  if (ShouldIssueConnect()) {
    BufferInput(true);
  }
  ret = BufferedReadAdapter::Connect(proxy_);
  // TODO: Set state_ appropriately if Connect fails.
  return ret;
}

SocketAddress AsyncHttpsProxySocket::GetRemoteAddress() const {
  return dest_;
}

int AsyncHttpsProxySocket::Close() {
  headers_.clear();
  state_ = PS_ERROR;
  dest_.Clear();
  delete context_;
  context_ = nullptr;
  return BufferedReadAdapter::Close();
}

Socket::ConnState AsyncHttpsProxySocket::GetState() const {
  if (state_ < PS_TUNNEL) {
    return CS_CONNECTING;
  } else if (state_ == PS_TUNNEL) {
    return CS_CONNECTED;
  } else {
    return CS_CLOSED;
  }
}

void AsyncHttpsProxySocket::OnConnectEvent(Socket* socket) {
  RTC_LOG(LS_VERBOSE) << "AsyncHttpsProxySocket::OnConnectEvent";
  if (!ShouldIssueConnect()) {
    state_ = PS_TUNNEL;
    BufferedReadAdapter::OnConnectEvent(socket);
    return;
  }
  SendRequest();
}

void AsyncHttpsProxySocket::OnCloseEvent(Socket* socket, int err) {
  RTC_LOG(LS_VERBOSE) << "AsyncHttpsProxySocket::OnCloseEvent(" << err << ")";
  if ((state_ == PS_WAIT_CLOSE) && (err == 0)) {
    state_ = PS_ERROR;
    Connect(dest_);
  } else {
    BufferedReadAdapter::OnCloseEvent(socket, err);
  }
}

void AsyncHttpsProxySocket::ProcessInput(char* data, size_t* len) {
  size_t start = 0;
  for (size_t pos = start; state_ < PS_TUNNEL && pos < *len;) {
    if (state_ == PS_SKIP_BODY) {
      size_t consume = std::min(*len - pos, content_length_);
      pos += consume;
      start = pos;
      content_length_ -= consume;
      if (content_length_ == 0) {
        EndResponse();
      }
      continue;
    }

    if (data[pos++] != '\n')
      continue;

    size_t length = pos - start - 1;
    if ((length > 0) && (data[start + length - 1] == '\r'))
      --length;

    data[start + length] = 0;
    ProcessLine(data + start, length);
    start = pos;
  }

  *len -= start;
  if (*len > 0) {
    memmove(data, data + start, *len);
  }

  if (state_ != PS_TUNNEL)
    return;

  bool remainder = (*len > 0);
  BufferInput(false);
  SignalConnectEvent(this);

  // FIX: if SignalConnect causes the socket to be destroyed, we are in trouble
  if (remainder)
    SignalReadEvent(this);  // TODO: signal this??
}

bool AsyncHttpsProxySocket::ShouldIssueConnect() const {
  // TODO: Think about whether a more sophisticated test
  // than dest port == 80 is needed.
  return force_connect_ || (dest_.port() != 80);
}

void AsyncHttpsProxySocket::SendRequest() {
  rtc::StringBuilder ss;
  ss << "CONNECT " << dest_.ToString() << " HTTP/1.0\r\n";
  ss << "User-Agent: " << agent_ << "\r\n";
  ss << "Host: " << dest_.HostAsURIString() << "\r\n";
  ss << "Content-Length: 0\r\n";
  ss << "Proxy-Connection: Keep-Alive\r\n";
  ss << headers_;
  ss << "\r\n";
  std::string str = ss.str();
  DirectSend(str.c_str(), str.size());
  state_ = PS_LEADER;
  expect_close_ = true;
  content_length_ = 0;
  headers_.clear();

  RTC_LOG(LS_VERBOSE) << "AsyncHttpsProxySocket >> " << str;
}

void AsyncHttpsProxySocket::ProcessLine(char* data, size_t len) {
  RTC_LOG(LS_VERBOSE) << "AsyncHttpsProxySocket << " << data;

  if (len == 0) {
    if (state_ == PS_TUNNEL_HEADERS) {
      state_ = PS_TUNNEL;
    } else if (state_ == PS_ERROR_HEADERS) {
      Error(defer_error_);
      return;
    } else if (state_ == PS_SKIP_HEADERS) {
      if (content_length_) {
        state_ = PS_SKIP_BODY;
      } else {
        EndResponse();
        return;
      }
    } else {
      if (!unknown_mechanisms_.empty()) {
        RTC_LOG(LS_ERROR) << "Unsupported authentication methods: "
                          << unknown_mechanisms_;
      }
      // Unexpected end of headers
      Error(0);
      return;
    }
  } else if (state_ == PS_LEADER) {
    unsigned int code;
    if (sscanf(data, "HTTP/%*u.%*u %u", &code) != 1) {
      Error(0);
      return;
    }
    switch (code) {
      case 200:
        // connection good!
        state_ = PS_TUNNEL_HEADERS;
        return;
#if defined(HTTP_STATUS_PROXY_AUTH_REQ) && (HTTP_STATUS_PROXY_AUTH_REQ != 407)
#error Wrong code for HTTP_STATUS_PROXY_AUTH_REQ
#endif
      case 407:  // HTTP_STATUS_PROXY_AUTH_REQ
        state_ = PS_AUTHENTICATE;
        return;
      default:
        defer_error_ = 0;
        state_ = PS_ERROR_HEADERS;
        return;
    }
  } else if ((state_ == PS_AUTHENTICATE) &&
             absl::StartsWithIgnoreCase(data, "Proxy-Authenticate:")) {
    std::string response, auth_method;
    switch (HttpAuthenticate(absl::string_view(data + 19, len - 19), proxy_,
                             "CONNECT", "/", user_, pass_, context_, response,
                             auth_method)) {
      case HAR_IGNORE:
        RTC_LOG(LS_VERBOSE) << "Ignoring Proxy-Authenticate: " << auth_method;
        if (!unknown_mechanisms_.empty())
          unknown_mechanisms_.append(", ");
        unknown_mechanisms_.append(auth_method);
        break;
      case HAR_RESPONSE:
        headers_ = "Proxy-Authorization: ";
        headers_.append(response);
        headers_.append("\r\n");
        state_ = PS_SKIP_HEADERS;
        unknown_mechanisms_.clear();
        break;
      case HAR_CREDENTIALS:
        defer_error_ = SOCKET_EACCES;
        state_ = PS_ERROR_HEADERS;
        unknown_mechanisms_.clear();
        break;
      case HAR_ERROR:
        defer_error_ = 0;
        state_ = PS_ERROR_HEADERS;
        unknown_mechanisms_.clear();
        break;
    }
  } else if (absl::StartsWithIgnoreCase(data, "Content-Length:")) {
    content_length_ = strtoul(data + 15, 0, 0);
  } else if (absl::StartsWithIgnoreCase(data, "Proxy-Connection: Keep-Alive")) {
    expect_close_ = false;
    /*
  } else if (absl::StartsWithIgnoreCase(data, "Connection: close") {
    expect_close_ = true;
    */
  }
}

void AsyncHttpsProxySocket::EndResponse() {
  if (!expect_close_) {
    SendRequest();
    return;
  }

  // No point in waiting for the server to close... let's close now
  // TODO: Refactor out PS_WAIT_CLOSE
  state_ = PS_WAIT_CLOSE;
  BufferedReadAdapter::Close();
  OnCloseEvent(this, 0);
}

void AsyncHttpsProxySocket::Error(int error) {
  BufferInput(false);
  Close();
  SetError(error);
  SignalCloseEvent(this, error);
}

///////////////////////////////////////////////////////////////////////////////

namespace {

// Encodes SOCKS5 UDP header into `out` for a datagram destined to `dst`.
// Layout: RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT
// Returns number of bytes written, or 0 if `dst` has an unsupported family.
size_t WriteSocksUdpHeader(const SocketAddress& dst, uint8_t* out) {
  size_t n = 0;
  out[n++] = 0x00;  // RSV
  out[n++] = 0x00;  // RSV
  out[n++] = 0x00;  // FRAG (no fragmentation)

  const IPAddress& ip = dst.ipaddr();
  if (ip.family() == AF_INET) {
    out[n++] = 0x01;  // ATYP = IPv4
    in_addr v4 = ip.ipv4_address();
    memcpy(out + n, &v4.s_addr, 4);
    n += 4;
  } else if (ip.family() == AF_INET6) {
    out[n++] = 0x04;  // ATYP = IPv6
    in6_addr v6 = ip.ipv6_address();
    memcpy(out + n, &v6.s6_addr, 16);
    n += 16;
  } else {
    return 0;
  }

  uint16_t port_be = HostToNetwork16(dst.port());
  memcpy(out + n, &port_be, 2);
  n += 2;
  return n;
}

// Parses SOCKS5 UDP header from `data[0..len]`. On success sets `*src` and
// `*header_len` and returns true. Does not validate FRAG != 0 (dropped).
bool ParseSocksUdpHeader(const uint8_t* data, size_t len, SocketAddress* src,
                         size_t* header_len) {
  if (len < 4) return false;
  if (data[2] != 0x00) return false;  // reject fragmented datagrams
  uint8_t atyp = data[3];
  size_t addr_off = 4;
  size_t addr_len;
  IPAddress ip;
  switch (atyp) {
    case 0x01: {
      addr_len = 4;
      if (len < addr_off + addr_len + 2) return false;
      in_addr v4;
      memcpy(&v4.s_addr, data + addr_off, 4);
      ip = IPAddress(v4);
      break;
    }
    case 0x04: {
      addr_len = 16;
      if (len < addr_off + addr_len + 2) return false;
      in6_addr v6;
      memcpy(&v6.s6_addr, data + addr_off, 16);
      ip = IPAddress(v6);
      break;
    }
    case 0x03: {
      if (len < addr_off + 1) return false;
      addr_len = 1 + static_cast<size_t>(data[addr_off]);
      if (len < addr_off + addr_len + 2) return false;
      // Domain in a SOCKS UDP reply is unusual for us; reject.
      return false;
    }
    default:
      return false;
  }
  uint16_t port_be;
  memcpy(&port_be, data + addr_off + addr_len, 2);
  uint16_t port = NetworkToHost16(port_be);
  *src = SocketAddress(ip, port);
  *header_len = addr_off + addr_len + 2;
  return true;
}

}  // namespace

AsyncSocksProxyUdpSocket::AsyncSocksProxyUdpSocket(
    SocketFactory* socket_factory, const SocketAddress& local_bind,
    const SocketAddress& socks_server)
    : socks_server_(socks_server) {
  // Create and bind the data-path UDP socket. If bind fails, udp_ stays null
  // and IsBound() will return false — caller should delete us.
  Socket* raw_udp = socket_factory->CreateSocket(local_bind.family(), SOCK_DGRAM);
  if (!raw_udp) {
    error_ = EIO;
    stage_ = ST_ERROR;
    return;
  }
  udp_.reset(AsyncUDPSocket::Create(raw_udp, local_bind));
  if (!udp_) {
    error_ = EIO;
    stage_ = ST_ERROR;
    return;
  }
  udp_->RegisterReceivedPacketCallback(
      [this](AsyncPacketSocket* s, const ReceivedPacket& pkt) {
        OnUdpPacket(s, pkt);
      });

  // Open the TCP control connection to the SOCKS server.
  control_.reset(socket_factory->CreateSocket(socks_server_.family(), SOCK_STREAM));
  if (!control_) {
    error_ = EIO;
    stage_ = ST_ERROR;
    return;
  }
  control_->SignalConnectEvent.connect(this, &AsyncSocksProxyUdpSocket::OnControlConnect);
  control_->SignalReadEvent.connect(this, &AsyncSocksProxyUdpSocket::OnControlRead);
  control_->SignalCloseEvent.connect(this, &AsyncSocksProxyUdpSocket::OnControlClose);

  int r = control_->Connect(socks_server_);
  if (r < 0 && control_->GetError() != EINPROGRESS && control_->GetError() != 0) {
    RTC_LOG(LS_ERROR) << "AsyncSocksProxyUdpSocket: control Connect failed: "
                      << control_->GetError();
    error_ = control_->GetError();
    stage_ = ST_ERROR;
    return;
  }
}

AsyncSocksProxyUdpSocket::~AsyncSocksProxyUdpSocket() = default;

bool AsyncSocksProxyUdpSocket::IsBound() const {
  return udp_ != nullptr;
}

SocketAddress AsyncSocksProxyUdpSocket::GetLocalAddress() const {
  return udp_ ? udp_->GetLocalAddress() : SocketAddress();
}

SocketAddress AsyncSocksProxyUdpSocket::GetRemoteAddress() const {
  return SocketAddress();
}

int AsyncSocksProxyUdpSocket::Send(const void* /*pv*/, size_t /*cb*/,
                                   const PacketOptions& /*options*/) {
  // UDP sockets don't support connected Send without a destination.
  SetError(ENOTCONN);
  return -1;
}

int AsyncSocksProxyUdpSocket::SendTo(const void* pv, size_t cb,
                                     const SocketAddress& addr,
                                     const PacketOptions& options) {
  if (stage_ != ST_READY) {
    SetError(EWOULDBLOCK);
    return -1;
  }
  if (!udp_) {
    SetError(ENOTCONN);
    return -1;
  }
  // Max SOCKS UDP header: 3 + 1 + 16 + 2 = 22 bytes.
  // Use a stack buffer large enough for typical RTP/STUN payloads.
  constexpr size_t kHeaderMax = 22;
  constexpr size_t kMaxPayload = 64 * 1024;
  if (cb > kMaxPayload) {
    SetError(EMSGSIZE);
    return -1;
  }
  uint8_t stackbuf[kHeaderMax + 2048];
  std::unique_ptr<uint8_t[]> heapbuf;
  uint8_t* buf = stackbuf;
  if (cb + kHeaderMax > sizeof(stackbuf)) {
    heapbuf.reset(new uint8_t[cb + kHeaderMax]);
    buf = heapbuf.get();
  }
  size_t hdr_len = WriteSocksUdpHeader(addr, buf);
  if (hdr_len == 0) {
    SetError(EAFNOSUPPORT);
    return -1;
  }
  memcpy(buf + hdr_len, pv, cb);
  int sent = udp_->SendTo(buf, hdr_len + cb, udp_relay_, options);
  if (sent < 0) return sent;
  // Report back the payload size (not including our header).
  return sent > static_cast<int>(hdr_len) ? sent - static_cast<int>(hdr_len)
                                          : 0;
}

int AsyncSocksProxyUdpSocket::Close() {
  if (control_) control_->Close();
  int r = udp_ ? udp_->Close() : 0;
  stage_ = ST_ERROR;
  return r;
}

AsyncPacketSocket::State AsyncSocksProxyUdpSocket::GetState() const {
  if (!udp_) return STATE_CLOSED;
  return STATE_BOUND;
}

int AsyncSocksProxyUdpSocket::GetOption(Socket::Option opt, int* value) {
  return udp_ ? udp_->GetOption(opt, value) : -1;
}

int AsyncSocksProxyUdpSocket::SetOption(Socket::Option opt, int value) {
  return udp_ ? udp_->SetOption(opt, value) : -1;
}

int AsyncSocksProxyUdpSocket::GetError() const {
  return error_;
}

void AsyncSocksProxyUdpSocket::SetError(int error) {
  error_ = error;
}

void AsyncSocksProxyUdpSocket::OnControlConnect(Socket* /*s*/) {
  SendGreeting();
}

void AsyncSocksProxyUdpSocket::SendGreeting() {
  const uint8_t buf[3] = {0x05, 0x01, 0x00};
  int r = control_->Send(buf, sizeof(buf));
  if (r != static_cast<int>(sizeof(buf))) {
    Fail(control_->GetError());
    return;
  }
  stage_ = ST_HELLO;
}

void AsyncSocksProxyUdpSocket::SendAssociate() {
  // VER CMD RSV ATYP DST.ADDR DST.PORT — ask for UDP ASSOCIATE with
  // DST=0.0.0.0:0 (we don't yet know our own source port, let the server
  // accept packets from any port we use).
  const uint8_t req[10] = {
      0x05, 0x03, 0x00, 0x01,
      0x00, 0x00, 0x00, 0x00,  // 0.0.0.0
      0x00, 0x00                // port 0
  };
  int r = control_->Send(req, sizeof(req));
  if (r != static_cast<int>(sizeof(req))) {
    Fail(control_->GetError());
    return;
  }
  stage_ = ST_ASSOCIATE;
}

void AsyncSocksProxyUdpSocket::OnControlRead(Socket* /*s*/) {
  while (true) {
    if (ctrl_len_ >= sizeof(ctrl_buf_)) {
      Fail(EOVERFLOW);
      return;
    }
    int r = control_->Recv(ctrl_buf_ + ctrl_len_,
                           sizeof(ctrl_buf_) - ctrl_len_, nullptr);
    if (r <= 0) {
      // -1 with EWOULDBLOCK: no more data right now
      break;
    }
    ctrl_len_ += static_cast<size_t>(r);
  }

  bool keep_parsing = true;
  while (keep_parsing) {
    if (stage_ == ST_HELLO) {
      keep_parsing = HandleHelloReply();
    } else if (stage_ == ST_ASSOCIATE) {
      keep_parsing = HandleAssociateReply();
    } else {
      keep_parsing = false;
    }
  }
}

bool AsyncSocksProxyUdpSocket::HandleHelloReply() {
  if (ctrl_len_ < 2) return false;
  if (ctrl_buf_[0] != 0x05 || ctrl_buf_[1] != 0x00) {
    RTC_LOG(LS_WARNING)
        << "AsyncSocksProxyUdpSocket: bad hello reply ver="
        << static_cast<int>(ctrl_buf_[0])
        << " method=" << static_cast<int>(ctrl_buf_[1]);
    Fail(EPROTO);
    return false;
  }
  // consume 2 bytes
  ctrl_len_ -= 2;
  if (ctrl_len_ > 0) memmove(ctrl_buf_, ctrl_buf_ + 2, ctrl_len_);
  SendAssociate();
  return stage_ == ST_ASSOCIATE;  // continue parsing if more bytes arrived
}

bool AsyncSocksProxyUdpSocket::HandleAssociateReply() {
  if (ctrl_len_ < 5) return false;
  if (ctrl_buf_[0] != 0x05 || ctrl_buf_[2] != 0x00) {
    RTC_LOG(LS_WARNING) << "AsyncSocksProxyUdpSocket: malformed ASSOCIATE reply";
    Fail(EPROTO);
    return false;
  }
  if (ctrl_buf_[1] != 0x00) {
    RTC_LOG(LS_WARNING) << "AsyncSocksProxyUdpSocket: ASSOCIATE rejected, REP="
                        << static_cast<int>(ctrl_buf_[1]);
    Fail(EHOSTUNREACH);
    return false;
  }
  uint8_t atyp = ctrl_buf_[3];
  size_t addr_off = 4;
  size_t addr_len;
  IPAddress ip;
  switch (atyp) {
    case 0x01:
      addr_len = 4;
      if (ctrl_len_ < addr_off + addr_len + 2) return false;
      {
        in_addr v4;
        memcpy(&v4.s_addr, ctrl_buf_ + addr_off, 4);
        ip = IPAddress(v4);
      }
      break;
    case 0x04:
      addr_len = 16;
      if (ctrl_len_ < addr_off + addr_len + 2) return false;
      {
        in6_addr v6;
        memcpy(&v6.s6_addr, ctrl_buf_ + addr_off, 16);
        ip = IPAddress(v6);
      }
      break;
    default:
      RTC_LOG(LS_WARNING)
          << "AsyncSocksProxyUdpSocket: unexpected ATYP in ASSOCIATE reply: "
          << static_cast<int>(atyp);
      Fail(EPROTO);
      return false;
  }
  uint16_t port_be;
  memcpy(&port_be, ctrl_buf_ + addr_off + addr_len, 2);
  uint16_t port = NetworkToHost16(port_be);

  // Per RFC 1928 the server may reply with 0.0.0.0 — it means "the same host
  // the TCP control is connected to." xray behaves this way.
  if (ip.IsNil() || (atyp == 0x01 && ip.ToString() == "0.0.0.0")) {
    udp_relay_ = SocketAddress(socks_server_.ipaddr(), port);
  } else {
    udp_relay_ = SocketAddress(ip, port);
  }

  size_t consumed = addr_off + addr_len + 2;
  ctrl_len_ -= consumed;
  if (ctrl_len_ > 0) memmove(ctrl_buf_, ctrl_buf_ + consumed, ctrl_len_);

  stage_ = ST_READY;
  RTC_LOG(LS_INFO) << "AsyncSocksProxyUdpSocket: relay ready at "
                   << udp_relay_.ToSensitiveString();
  // Notify upper layer that the local address (UDP port) is now usable.
  // AsyncUDPSocket already returns BOUND after bind, so ICE will pick it up
  // on its next tick.
  SignalAddressReady(this, GetLocalAddress());
  return false;
}

void AsyncSocksProxyUdpSocket::OnControlClose(Socket* /*s*/, int err) {
  if (stage_ != ST_READY) {
    RTC_LOG(LS_WARNING)
        << "AsyncSocksProxyUdpSocket: control closed during handshake, err="
        << err;
  } else {
    RTC_LOG(LS_INFO) << "AsyncSocksProxyUdpSocket: control closed, relay lost";
  }
  stage_ = ST_ERROR;
  error_ = err ? err : ECONNRESET;
  NotifyClosed(error_);
}

void AsyncSocksProxyUdpSocket::OnUdpPacket(AsyncPacketSocket* /*s*/,
                                           const ReceivedPacket& packet) {
  if (stage_ != ST_READY) return;
  // Only accept datagrams from the SOCKS relay endpoint.
  if (packet.source_address() != udp_relay_) {
    RTC_LOG(LS_VERBOSE)
        << "AsyncSocksProxyUdpSocket: ignoring packet from "
        << packet.source_address().ToSensitiveString();
    return;
  }
  SocketAddress real_src;
  size_t hdr_len = 0;
  if (!ParseSocksUdpHeader(packet.payload().data(), packet.payload().size(),
                           &real_src, &hdr_len)) {
    RTC_LOG(LS_VERBOSE) << "AsyncSocksProxyUdpSocket: drop invalid UDP header";
    return;
  }
  auto payload = packet.payload().subview(hdr_len);
  ReceivedPacket forwarded(payload, real_src, packet.arrival_time());
  NotifyPacketReceived(forwarded);
}

void AsyncSocksProxyUdpSocket::Fail(int error) {
  if (stage_ == ST_ERROR) return;
  stage_ = ST_ERROR;
  error_ = error ? error : EPROTO;
  if (control_) control_->Close();
  NotifyClosed(error_);
}

}  // namespace rtc
