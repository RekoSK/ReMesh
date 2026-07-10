#pragma once

#include <MeshCore.h>
#include <helpers/ui/DisplayDriver.h>
#include <helpers/ui/UIScreen.h>
#include <helpers/SensorManager.h>
#include <helpers/BaseSerialInterface.h>
#include <Arduino.h>

#ifdef PIN_BUZZER
  #include <helpers/ui/buzzer.h>
#endif

#include "NodePrefs.h"

enum class UIEventType {
    none,
    contactMessage,
    channelMessage,
    roomMessage,
    newContactMessage,
    ack
};

class AbstractUITask {
protected:
  mesh::MainBoard* _board;
  BaseSerialInterface* _serial;
  bool _connected;

  AbstractUITask(mesh::MainBoard* board, BaseSerialInterface* serial) : _board(board), _serial(serial) {
    _connected = false;
  }

public:
  void setHasConnection(bool connected) { _connected = connected; }
  bool hasConnection() const { return _connected; }
  uint16_t getBattMilliVolts() const { return _board->getBattMilliVolts(); }
  bool isCharging() const { return _board->isCharging(); }
  bool isExternalPowered() const { return _board->isExternalPowered(); }
  bool isSerialEnabled() const { return _serial->isEnabled(); }
  void enableSerial() { _serial->enable(); }
  void disableSerial() { _serial->disable(); }
  bool isPairing() const { return _serial->isPairing(); }
  int connectionRssi() const { return _serial->getConnectionRssi(); }
  virtual void msgRead(int msgcount) = 0;
  virtual void newMsg(uint8_t path_len, const char* from_name, const char* text, int msgcount) = 0;

  // Channel messages, with the channel index the older newMsg() hook throws
  // away. Defaults to the legacy behaviour so ui-orig / ui-tiny need no change.
  virtual void newChannelMsg(uint8_t channel_idx, const char* channel_name, uint8_t path_len,
                             const char* text, int msgcount) {
    newMsg(path_len, channel_name, text, msgcount);
  }

  // A node answered our device-side scan. rx_snr4 is the SNR we heard the reply
  // at; tx_snr4 is the SNR the node heard *us* at. Both x4. Default no-op, so
  // ui-orig / ui-tiny are unaffected.
  virtual void nodeDiscovered(uint8_t node_type, int8_t rx_snr4, int8_t tx_snr4,
                              const uint8_t* pub_key, uint8_t key_len) { }
  virtual void notify(UIEventType t = UIEventType::none) = 0;
  virtual void loop() = 0;
};
