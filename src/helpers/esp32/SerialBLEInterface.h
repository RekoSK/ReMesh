#pragma once

#include "../BaseSerialInterface.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_gap_ble_api.h>

class SerialBLEInterface : public BaseSerialInterface, BLESecurityCallbacks, BLEServerCallbacks, BLECharacteristicCallbacks {
  BLEServer *pServer;
  BLEService *pService;
  BLECharacteristic * pTxCharacteristic;
  bool deviceConnected;
  bool oldDeviceConnected;
  bool _isEnabled;
  uint16_t last_conn_id;
  uint32_t _pin_code;
  unsigned long _last_write;
  unsigned long adv_restart_time;

  // set from the BLE stack task, read from the main loop
  volatile bool _pairing;
  volatile unsigned long _pairing_expiry;

  // Link RSSI. esp_ble_gap_read_rssi() is asynchronous: the reply lands in
  // gapHandler() on the BLE stack task, so _rssi is written there and read from
  // the main loop. 0 = no reading yet.
  esp_bd_addr_t _peer_addr;
  bool _have_peer;
  volatile int8_t _rssi;
  unsigned long _next_rssi_req;

  static SerialBLEInterface* _instance;   // gapHandler() is a plain C callback
  static void gapHandler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t* param);
  void pollRssi();

  struct Frame {
    uint8_t len;
    uint8_t buf[MAX_FRAME_SIZE];
  };

  #define FRAME_QUEUE_SIZE  4
  int recv_queue_len;
  Frame recv_queue[FRAME_QUEUE_SIZE];
  int send_queue_len;
  Frame send_queue[FRAME_QUEUE_SIZE];

  void clearBuffers() { recv_queue_len = 0; send_queue_len = 0; }
  void markPairing();

protected:
  // BLESecurityCallbacks methods
  uint32_t onPassKeyRequest() override;
  void onPassKeyNotify(uint32_t pass_key) override;
  bool onConfirmPIN(uint32_t pass_key) override;
  bool onSecurityRequest() override;
  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override;

  // BLEServerCallbacks methods
  void onConnect(BLEServer* pServer) override;
  void onConnect(BLEServer* pServer, esp_ble_gatts_cb_param_t *param) override;
  void onMtuChanged(BLEServer* pServer, esp_ble_gatts_cb_param_t* param) override;
  void onDisconnect(BLEServer* pServer) override;

  // BLECharacteristicCallbacks methods
  void onWrite(BLECharacteristic* pCharacteristic, esp_ble_gatts_cb_param_t* param) override;

public:
  SerialBLEInterface() {
    pServer = NULL;
    pService = NULL;
    deviceConnected = false;
    oldDeviceConnected = false;
    adv_restart_time = 0;
    _pairing = false;
    _pairing_expiry = 0;
    _isEnabled = false;
    _last_write = 0;
    last_conn_id = 0;
    send_queue_len = recv_queue_len = 0;
    memset(_peer_addr, 0, sizeof(_peer_addr));
    _have_peer = false;
    _rssi = 0;
    _next_rssi_req = 0;
  }

  /**
   * init the BLE interface.
   * @param prefix   a prefix for the device name
   * @param name  IN/OUT - a name for the device (combined with prefix). If "@@MAC", is modified and returned
   * @param pin_code   the BLE security pin
   */
  void begin(const char* prefix, char* name, uint32_t pin_code);

  // BaseSerialInterface methods
  void enable() override;
  void disable() override;
  bool isEnabled() const override { return _isEnabled; }

  bool isConnected() const override;
  bool isPairing() const override;
  int getConnectionRssi() const override;

  bool isWriteBusy() const override;
  size_t writeFrame(const uint8_t src[], size_t len) override;
  size_t checkRecvFrame(uint8_t dest[]) override;
};

#if BLE_DEBUG_LOGGING && ARDUINO
  #include <Arduino.h>
  #define BLE_DEBUG_PRINT(F, ...) Serial.printf("BLE: " F, ##__VA_ARGS__)
  #define BLE_DEBUG_PRINTLN(F, ...) Serial.printf("BLE: " F "\n", ##__VA_ARGS__)
#else
  #define BLE_DEBUG_PRINT(...) {}
  #define BLE_DEBUG_PRINTLN(...) {}
#endif
