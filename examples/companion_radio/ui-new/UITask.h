#pragma once

#include <MeshCore.h>
#include <helpers/ui/DisplayDriver.h>
#include <helpers/ui/UIScreen.h>
#include <helpers/SensorManager.h>
#include <helpers/BaseSerialInterface.h>
#include <Arduino.h>
#include <helpers/sensors/LPPDataHelpers.h>

#ifndef LED_STATE_ON
  #define LED_STATE_ON 1
#endif

#ifdef PIN_BUZZER
  #include <helpers/ui/buzzer.h>
#endif
#ifdef PIN_VIBRATION
  #include <helpers/ui/GenericVibration.h>
#endif

#include "../AbstractUITask.h"
#include "../NodePrefs.h"
#include "MsgStore.h"

// Results of a device-side node scan. Kept in RAM only.
#ifndef UI_MAX_SCAN_RESULTS
  #define UI_MAX_SCAN_RESULTS  12
#endif
#define UI_SCAN_KEY_LEN        32    // full public key, so a contact can be added

// how long we keep collecting replies after firing a scan. Responders back off by
// getRetransmitDelay()*4, which is well under a second for a control packet.
#define UI_SCAN_WINDOW_MILLIS  5000

#define UI_SCAN_INTERVAL_BUSY_MILLIS  (15UL * 60 * 1000)   // heard traffic
#define UI_SCAN_INTERVAL_IDLE_MILLIS  ( 3UL * 60 * 1000)   // nothing found / nothing heard

struct AdvertPath;   // defined in ../MyMesh.h

// what NodeInfoScreen draws below the title
#define NODEINFO_SIGNAL   1    // 16-hex key + IN/OUT signal meters
#define NODEINFO_FULLKEY  2    // the whole public key, 16 hex chars per line

struct ScanResult {
  uint8_t  key[UI_SCAN_KEY_LEN];
  uint8_t  key_len;
  uint8_t  node_type;
  int8_t   rx_snr4;       // SNR we heard the reply at, x4   (incoming)
  int8_t   tx_snr4;       // SNR the node heard us at, x4    (outgoing)
  unsigned long seen;     // millis() of the reply
};

class UITask : public AbstractUITask {
  DisplayDriver* _display;
  SensorManager* _sensors;
#ifdef PIN_BUZZER
  genericBuzzer buzzer;
#endif
#ifdef PIN_VIBRATION
  GenericVibration vibration;
#endif
  unsigned long _next_refresh, _auto_off;
  NodePrefs* _node_prefs;
  char _alert[80];
  unsigned long _alert_expiry;
  int _msgcount;
  unsigned long ui_started_at, next_batt_chck;
  int next_backlight_btn_check = 0;
#ifdef PIN_STATUS_LED
  int led_state = 0;
  int next_led_change = 0;
  int last_led_increment = 0;
#endif

#ifdef PIN_USER_BTN_ANA
  unsigned long _analogue_pin_read_millis = millis();
#endif

  UIScreen* splash;
  UIScreen* home;
  UIScreen* chan_view;
  UIScreen* morse_view;
  UIScreen* node_info;
  UIScreen* path_view;
  UIScreen* matches_view;
  UIScreen* curr;
  uint8_t _info_origin;    // 0 = Scan page, 1 = Recent page, 2 = path view
  MsgStore _msgs;
  bool _was_pairing;

  // --- node scan ---
  ScanResult _scan[UI_MAX_SCAN_RESULTS];
  int  _scan_count;
  int  _scan_hits;              // replies to the scan currently in flight
  unsigned long _scan_deadline; // 0 = no scan in flight
  bool _autoscan;
  unsigned long _next_autoscan;
  uint32_t _pkts_at_last_scan;

  // --- link activity, for the top-bar signal glyph ---
  unsigned long _last_activity;   // millis of last received packet or scan reply
  uint32_t _last_pkt_count;

  void userLedHandler();
  void wakeForMsg();

  // Button action handlers
  char checkDisplayOn(char c);
  char handleLongPress(char c);
  char handleDoubleClick(char c);
  char handleTripleClick(char c);

  void setCurrScreen(UIScreen* c);

public:

  UITask(mesh::MainBoard* board, BaseSerialInterface* serial) : AbstractUITask(board, serial), _display(NULL), _sensors(NULL) {
    next_batt_chck = _next_refresh = 0;
    ui_started_at = 0;
    curr = NULL;
    _was_pairing = false;
    _scan_count = _scan_hits = 0;
    _scan_deadline = 0;
    _autoscan = false;
    _next_autoscan = 0;
    _pkts_at_last_scan = 0;
    _last_activity = 0;
    _last_pkt_count = 0;
    _info_origin = 0;
  }
  void begin(DisplayDriver* display, SensorManager* sensors, NodePrefs* node_prefs);

  void gotoHomeScreen() { setCurrScreen(home); }

  MsgStore& msgs() { return _msgs; }

  // --- node scan ---
  void startScan();
  bool isScanning() const { return _scan_deadline != 0; }
  bool isAutoscan() const { return _autoscan; }
  void toggleAutoscan();
  int  scanCount() const { return _scan_count; }
  const ScanResult& scanResult(int i) const { return _scan[i]; }   // newest first

  // 0 = nothing heard for 15 min (glyph hidden), 1..3 = how recent
  int signalBars() const;

  void openChannelView(const MsgRowKey& key, const char* title);
  void closeChannelView();       // marks read, returns to Channels page keeping control
  void openMorseCompose(const MsgRowKey& key, const char* title);
  void closeMorseCompose();      // back to the channel reading view
  bool sendComposedText(const MsgRowKey& key, const char* text);
  bool isDisplayOn() const { return _display != NULL && _display->isOn(); }
  void gotoBluetoothScreen();    // used when a peer starts pairing

  void openNodeInfo(const ScanResult& r);          // from the Scan page
  void openAdvertInfo(const AdvertPath& a);       // from the Recent page
  void openPathView(const AdvertPath& a);
  void openMatches(const uint8_t* hash, uint8_t hash_len);
  void closeNodeInfo();          // returns to whichever page opened it
  void closePathView();          // back to Recent, keeping control
  void closeMatches();           // back to the path view
  void showAlert(const char* text, int duration_millis);
  int  getMsgCount() const { return _msgcount; }
  bool hasDisplay() const { return _display != NULL; }
  bool isButtonPressed() const;

  bool isBuzzerQuiet() { 
#ifdef PIN_BUZZER
    return buzzer.isQuiet();
#else
    return true;
#endif
  }

  void toggleBuzzer();
  bool getGPSState();
  void toggleGPS();


  // from AbstractUITask
  void msgRead(int msgcount) override;
  void newMsg(uint8_t path_len, const char* from_name, const char* text, int msgcount) override;
  void newChannelMsg(uint8_t channel_idx, const char* channel_name, uint8_t path_len,
                     const char* text, int msgcount) override;
  void nodeDiscovered(uint8_t node_type, int8_t rx_snr4, int8_t tx_snr4,
                      const uint8_t* pub_key, uint8_t key_len) override;
  void notify(UIEventType t = UIEventType::none) override;
  void loop() override;

  void shutdown(bool restart = false);
};
