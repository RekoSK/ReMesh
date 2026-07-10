#pragma once

#include <Arduino.h>

#define MAX_FRAME_SIZE  176   // +4 for transport codes (region scoping)

class BaseSerialInterface {
protected:
  BaseSerialInterface() { }

public:
  virtual void enable() = 0;
  virtual void disable() = 0;
  virtual bool isEnabled() const = 0;

  virtual bool isConnected() const = 0;

  // true while a peer is actively pairing and needs the PIN shown. Non-pure so
  // that transports without pairing (USB serial, wifi) need not implement it.
  virtual bool isPairing() const { return false; }

  // Signal strength of the link to the connected peer, in dBm. 0 means "not
  // known": no peer, the transport has no notion of signal, or no reading yet.
  virtual int getConnectionRssi() const { return 0; }

  virtual bool isWriteBusy() const = 0;
  virtual size_t writeFrame(const uint8_t src[], size_t len) = 0;
  virtual size_t checkRecvFrame(uint8_t dest[]) = 0;
};
