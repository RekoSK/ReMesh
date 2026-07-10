#include "UITask.h"
#include <helpers/TxtDataHelpers.h>
#include "../MyMesh.h"
#include "target.h"
#ifdef WIFI_SSID
  #include <WiFi.h>
#endif

#ifndef AUTO_OFF_MILLIS
  #define AUTO_OFF_MILLIS     15000   // 15 seconds
#endif
#define BOOT_SCREEN_MILLIS   3000   // 3 seconds

#ifdef PIN_STATUS_LED
#define LED_ON_MILLIS     20
#define LED_ON_MSG_MILLIS 200
#define LED_CYCLE_MILLIS  4000
#endif

#define LONG_PRESS_MILLIS   1200

#ifndef UI_RECENT_LIST_SIZE
  #define UI_RECENT_LIST_SIZE 4
#endif

// Meshtastic-style chrome: a status bar across the top, and a navigation bar
// along the bottom that appears on page change then hides again, so the page
// content keeps the full height of the screen.
#ifndef UI_NAVBAR_MILLIS
  #define UI_NAVBAR_MILLIS  3000    // 0 = always visible
#endif
#define UI_TOPBAR_SEP_Y     12      // y of the 1px separator under the top bar
#define UI_NAV_ICON_SIZE    8
#define UI_NAV_ICON_GAP     4

#if UI_HAS_JOYSTICK
  #define PRESS_LABEL "press Enter"
#else
  #define PRESS_LABEL "long press"
#endif

#include "icons.h"

// ---- shared drawing helpers ----

// SNR (dB) -> 0..4 bars
static int snrToBars(int8_t snr4) {
  int snr = snr4 / 4;
  if (snr >= 10) return 4;
  if (snr >=  5) return 3;
  if (snr >=  0) return 2;
  if (snr >= -7) return 1;
  return 0;
}

// BLE link RSSI (dBm) -> 0..4 bars. 0 dBm means "no reading" (see
// BaseSerialInterface::getConnectionRssi), not a perfect link.
static int rssiToBars(int rssi) {
  if (rssi == 0) return 0;
  if (rssi >= -60) return 4;
  if (rssi >= -72) return 3;
  if (rssi >= -84) return 2;
  if (rssi >= -96) return 1;
  return 0;
}

// Cellular-style meter: four 3px bars, 2px apart, heights 3/6/9/12.
// Occupies 18x12 at (x,y). Inactive bars are outlined, active ones filled.
static void uiDrawSignalMeter(DisplayDriver& display, int x, int y, int level) {
  for (int b = 0; b < 4; b++) {
    int h = 3 * (b + 1);
    int bx = x + b * 5;
    int by = y + 12 - h;
    if (b < level) display.fillRect(bx, by, 3, h);
    else           display.drawRect(bx, by, 3, h);
  }
}

// Modal popup box, vertically centred in the content area, one row per item.
static void uiRenderMenu(DisplayDriver& display, const char* const* items, int n, int sel) {
  const int bx = 4, bw = display.width() - 8;
  const int bh = 11 * n + 4;
  const int by = 14 + (50 - bh) / 2;

  display.setColor(DisplayDriver::DARK);
  display.fillRect(bx, by, bw, bh);          // hide the page behind it
  display.setColor(DisplayDriver::LIGHT);
  display.drawRect(bx, by, bw, bh);

  display.setTextSize(1);
  for (int i = 0; i < n; i++) {
    int y = by + 4 + i * 11;
    if (i == sel) {
      display.setColor(DisplayDriver::LIGHT);
      display.fillRect(bx + 1, y - 2, bw - 2, 11);
      display.setColor(DisplayDriver::DARK);
    } else {
      display.setColor(DisplayDriver::LIGHT);
    }
    display.drawTextEllipsized(bx + 3, y, bw - 6, items[i]);
  }
}

#if ENV_INCLUDE_GPS == 1
#include <math.h>

// DisplayDriver has no line or circle primitive, so plot 1px rects.
static void uiDrawLine(DisplayDriver& d, int x0, int y0, int x1, int y1) {
  int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
  int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
  int err = dx + dy;
  for (;;) {
    d.fillRect(x0, y0, 1, 1);
    if (x0 == x1 && y0 == y1) break;
    int e2 = 2 * err;
    if (e2 >= dy) { err += dy; x0 += sx; }
    if (e2 <= dx) { err += dx; y0 += sy; }
  }
}

// midpoint circle, outline only
static void uiDrawCircle(DisplayDriver& d, int cx, int cy, int r) {
  int x = r, y = 0, err = 1 - r;
  while (x >= y) {
    d.fillRect(cx + x, cy + y, 1, 1); d.fillRect(cx + y, cy + x, 1, 1);
    d.fillRect(cx - y, cy + x, 1, 1); d.fillRect(cx - x, cy + y, 1, 1);
    d.fillRect(cx - x, cy - y, 1, 1); d.fillRect(cx - y, cy - x, 1, 1);
    d.fillRect(cx + y, cy - x, 1, 1); d.fillRect(cx + x, cy - y, 1, 1);
    y++;
    if (err < 0) err += 2 * y + 1;
    else { x--; err += 2 * (y - x) + 1; }
  }
}
#endif

class SplashScreen : public UIScreen {
  UITask* _task;
  unsigned long dismiss_after;
  char _version_info[12];

public:
  SplashScreen(UITask* task) : _task(task) {
    // strip off dash and commit hash by changing dash to null terminator
    // e.g: v1.2.3-abcdef -> v1.2.3
    const char *ver = FIRMWARE_VERSION;
    const char *dash = strchr(ver, '-');

    int len = dash ? dash - ver : strlen(ver);
    if (len >= sizeof(_version_info)) len = sizeof(_version_info) - 1;
    memcpy(_version_info, ver, len);
    _version_info[len] = 0;

    dismiss_after = millis() + BOOT_SCREEN_MILLIS;
  }

  int render(DisplayDriver& display) override {
    // ReCore logo
    display.setColor(DisplayDriver::BLUE);
    const int logoSize = 40;
    display.drawXbm((display.width() - logoSize) / 2, 1, grafity_logo, logoSize, logoSize);

    // version info
    display.setColor(DisplayDriver::LIGHT);

    display.setTextSize(1);
    display.drawTextCentered(display.width()/2, 44, "ReMesh");

    char ver_line[8 + sizeof(_version_info)];
    snprintf(ver_line, sizeof(ver_line), "VER: %s", _version_info);
    display.setTextSize(1);
    display.drawTextCentered(display.width()/2, 54, ver_line);

    return 1000;
  }

  void poll() override {
    if (millis() >= dismiss_after) {
      _task->gotoHomeScreen();
    }
  }
};

#ifndef UI_MAX_CHAN_ROWS
  #define UI_MAX_CHAN_ROWS  24
#endif

class HomeScreen : public UIScreen {
  enum HomePage {
    FIRST,
    CHANNELS,
    RECENT,
    SCAN,
    RADIO,
    BLUETOOTH,
    ADVERT,
#if ENV_INCLUDE_GPS == 1
    GPS,
#endif
#if UI_SENSORS_PAGE == 1
    SENSORS,
#endif
    SHUTDOWN,
    Count    // keep as last
  };

  UITask* _task;
  mesh::RTCClock* _rtc;
  SensorManager* _sensors;
  NodePrefs* _node_prefs;
  uint8_t _page;
  bool _shutdown_init;
  unsigned long _page_changed_at;   // when the nav bar was last (re)shown
  bool _first_render;
  // --- Channels page state ---
  struct ChanRow {
    MsgRowKey key;
    char name[UI_MSG_SENDER_LEN];
  };
  ChanRow _rows[UI_MAX_CHAN_ROWS];
  int  _num_rows;
  bool _chan_control;   // long-press "took control" of the list
  int  _chan_sel;
  int  _chan_scroll;

  static const int CHAN_ROW_H  = 10;
  static const int CHAN_TOP_Y  = 15;
  static const int CHAN_VISIBLE = 4;   // rows 15,25,35,45 -- clear of the nav strip

  // --- Advert popup menu state ---
  bool _advert_menu;
  int  _advert_sel;
  static const int ADVERT_MENU_ITEMS = 3;   // Back / Flood / Zero-hop

  // --- Scan page state ---
  bool _scan_menu;      // the Back/Scan/Inspect/Autoscan popup
  int  _scan_sel;
  static const int SCAN_MENU_ITEMS = 4;     // Back / Scan nearby / Inspect / Autoscan

  bool _scan_ctl;       // "Inspect" took control of the list
  int  _scan_row;
  int  _scan_scroll;

  bool _rep_menu;       // the per-repeater popup (unknown node only)
  int  _rep_sel;
  static const int REP_MENU_ITEMS = 3;      // Back / Add to contacts / Show info

  // --- Recent page state ---
  bool _rec_ctl;        // long-press took control of the advert list
  int  _rec_row;
  int  _rec_scroll;
  bool _rec_menu;       // Back / Show more / Show path
  int  _rec_sel;
  static const int REC_MENU_ITEMS = 3;

  // "12s" / "5m" / "2h" for an age in millis, like the Recent page
  static void fmtAge(char* dest, unsigned long age_millis) {
    unsigned long secs = age_millis / 1000;
    if (secs < 60)         sprintf(dest, "%lus", secs);
    else if (secs < 3600)  sprintf(dest, "%lum", secs / 60);
    else                   sprintf(dest, "%luh", secs / 3600);
  }

  // Contact name if we know this node, else the first 4 hex chars of its key.
  void scanRowLabel(const ScanResult& r, char* dest, size_t dest_sz) {
    ContactInfo* c = the_mesh.lookupContactByPubKey(r.key, r.key_len);
    if (c != NULL && c->name[0] != 0) {
      strncpy(dest, c->name, dest_sz - 1);
      dest[dest_sz - 1] = 0;
    } else {
      snprintf(dest, dest_sz, "%02X%02X", r.key[0], r.key[1]);   // 4 hex chars
    }
  }

  bool scanIsKnownContact(const ScanResult& r) {
    return the_mesh.lookupContactByPubKey(r.key, r.key_len) != NULL;
  }

  void scrollToScanRow() {
    if (_scan_row < _scan_scroll) _scan_scroll = _scan_row;
    if (_scan_row >= _scan_scroll + CHAN_VISIBLE) _scan_scroll = _scan_row - CHAN_VISIBLE + 1;
    if (_scan_scroll < 0) _scan_scroll = 0;
  }

  // Same row layout and scrolling as the Channels page. Selection only exists
  // once "Inspect" has taken control.
  void renderScan(DisplayDriver& display) {
    display.setTextSize(1);
    display.setColor(DisplayDriver::LIGHT);

    const int n = _task->scanCount();
    if (n == 0) {
      display.drawTextCentered(display.width() / 2, 30,
          _task->isScanning() ? "Scanning..." : "(no repeaters)");
      return;
    }
    if (_scan_row >= n) _scan_row = n - 1;
    scrollToScanRow();

    const unsigned long now = millis();
    char label[UI_MSG_SENDER_LEN];
    char age[12];

    for (int i = 0; i < CHAN_VISIBLE; i++) {
      int idx = _scan_scroll + i;
      if (idx >= n) break;
      int y = CHAN_TOP_Y + i * CHAN_ROW_H;
      bool selected = _scan_ctl && (idx == _scan_row);

      const ScanResult& r = _task->scanResult(idx);
      fmtAge(age, now - r.seen);
      int age_w = display.getTextWidth(age);
      scanRowLabel(r, label, sizeof(label));

      if (selected) {
        display.setColor(DisplayDriver::LIGHT);
        display.fillRect(0, y - 1, display.width(), CHAN_ROW_H);
        display.setColor(DisplayDriver::DARK);
      } else {
        display.setColor(DisplayDriver::LIGHT);
      }

      char filtered[UI_MSG_SENDER_LEN];
      display.translateUTF8ToBlocks(filtered, label, sizeof(filtered));
      display.drawTextEllipsized(1, y, display.width() - age_w - 4, filtered);
      display.setCursor(display.width() - age_w - 1, y);
      display.print(age);
    }

    if (n > CHAN_VISIBLE) {   // scrollbar hint, as on the Channels page
      display.setColor(DisplayDriver::LIGHT);
      int track_h = CHAN_VISIBLE * CHAN_ROW_H;
      int knob_h = track_h * CHAN_VISIBLE / n;
      if (knob_h < 3) knob_h = 3;
      int knob_y = CHAN_TOP_Y - 1 + (track_h - knob_h) * _scan_scroll / (n - CHAN_VISIBLE);
      display.fillRect(display.width() - 1, knob_y, 1, knob_h);
    }
  }

  void renderScanMenu(DisplayDriver& display) {
    char autoscan[20];
    sprintf(autoscan, "Autoscan: %s", _task->isAutoscan() ? "On" : "Off");
    const char* items[SCAN_MENU_ITEMS] = { "Back", "Scan nearby", "Inspect", autoscan };
    uiRenderMenu(display, items, SCAN_MENU_ITEMS, _scan_sel);
  }

  void renderRepMenu(DisplayDriver& display) {
    static const char* items[REP_MENU_ITEMS] = { "Back", "Add to contacts", "Show info" };
    uiRenderMenu(display, items, REP_MENU_ITEMS, _rep_sel);
  }

  // long-press on a selected repeater: known contacts jump straight to the info
  // screen, unknown ones get the add/show popup
  void openSelectedRepeater() {
    if (_task->scanCount() == 0) return;
    const ScanResult& r = _task->scanResult(_scan_row);
    if (scanIsKnownContact(r)) {
      _task->openNodeInfo(r);
    } else {
      _rep_menu = true;
      _rep_sel = 0;
    }
  }

  void renderRecMenu(DisplayDriver& display) {
    static const char* items[REC_MENU_ITEMS] = { "Back", "Show more", "Show path" };
    uiRenderMenu(display, items, REC_MENU_ITEMS, _rec_sel);
  }

  bool handleRecMenuInput(char c) {
    if (c == KEY_NEXT || c == KEY_RIGHT) { _rec_sel = (_rec_sel + 1) % REC_MENU_ITEMS; return true; }
    if (c == KEY_PREV || c == KEY_LEFT)  { _rec_sel = (_rec_sel + REC_MENU_ITEMS - 1) % REC_MENU_ITEMS; return true; }
    if (c == KEY_ENTER) {
      int sel = _rec_sel;
      _rec_menu = false;
      int n = the_mesh.getRecentlyHeardCount();
      if (n == 0 || _rec_row >= n) return true;
      const AdvertPath* a = the_mesh.getRecentlyHeardAt(_rec_row);
      if (a == NULL) return true;
      if (sel == 1)      _task->openAdvertInfo(*a);
      else if (sel == 2) _task->openPathView(*a);
      return true;   // sel == 0 (Back)
    }
    return true;   // modal
  }

  // long-press takes control; then click = next, double-click = release,
  // long-press = open the popup for the selected advert
  bool handleRecentInput(char c) {
    if (!_rec_ctl) {
      if (c == KEY_ENTER) {
        if (the_mesh.getRecentlyHeardCount() == 0) return true;
        _rec_ctl = true; _rec_row = 0; _rec_scroll = 0;
        return true;
      }
      return false;   // let page navigation handle it
    }
    const int n = the_mesh.getRecentlyHeardCount();
    if (n == 0) { _rec_ctl = false; return false; }

    if (c == KEY_NEXT || c == KEY_RIGHT) {
      _rec_row = (_rec_row + 1) % n;
      if (_rec_row < _rec_scroll) _rec_scroll = _rec_row;
      if (_rec_row >= _rec_scroll + CHAN_VISIBLE) _rec_scroll = _rec_row - CHAN_VISIBLE + 1;
      if (_rec_scroll < 0) _rec_scroll = 0;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) { _rec_ctl = false; return true; }
    if (c == KEY_ENTER) { _rec_menu = true; _rec_sel = 0; return true; }
    return true;
  }

  bool handleScanMenuInput(char c) {
    if (c == KEY_NEXT || c == KEY_RIGHT) {
      _scan_sel = (_scan_sel + 1) % SCAN_MENU_ITEMS;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {
      _scan_sel = (_scan_sel + SCAN_MENU_ITEMS - 1) % SCAN_MENU_ITEMS;
      return true;
    }
    if (c == KEY_ENTER) {
      int sel = _scan_sel;
      _scan_menu = false;
      if (sel == 1) {
        _task->startScan();
        _task->showAlert("Scanning...", 1200);
      } else if (sel == 2) {                       // Inspect: take control
        if (_task->scanCount() == 0) {
          _task->showAlert("Nothing to inspect", 1000);
        } else {
          _scan_ctl = true;
          _scan_row = 0;
          _scan_scroll = 0;
        }
      } else if (sel == 3) {
        _task->toggleAutoscan();
        _task->showAlert(_task->isAutoscan() ? "Autoscan: On" : "Autoscan: Off", 1000);
      }
      return true;   // sel == 0 (Back) just closes
    }
    return true;   // modal
  }

  bool handleRepMenuInput(char c) {
    if (c == KEY_NEXT || c == KEY_RIGHT) {
      _rep_sel = (_rep_sel + 1) % REP_MENU_ITEMS;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {
      _rep_sel = (_rep_sel + REP_MENU_ITEMS - 1) % REP_MENU_ITEMS;
      return true;
    }
    if (c == KEY_ENTER) {
      int sel = _rep_sel;
      _rep_menu = false;
      if (_task->scanCount() == 0) return true;
      const ScanResult& r = _task->scanResult(_scan_row);

      if (sel == 1) {                     // Add to contacts, named by 4 hex chars
        char name[8];
        snprintf(name, sizeof(name), "%02X%02X", r.key[0], r.key[1]);
        if (the_mesh.addRepeaterContact(r.key, name)) {
          _task->showAlert("Added to contacts", 1200);
        } else {
          _task->showAlert("ERROR: Contacts full", 1500);
        }
      } else if (sel == 2) {
        _task->openNodeInfo(r);
      }
      return true;   // sel == 0 (Back) returns to the list, still in control
    }
    return true;   // modal
  }

  // click = next, double-click = leave control, long-press = open
  bool handleScanCtlInput(char c) {
    const int n = _task->scanCount();
    if (n == 0) { _scan_ctl = false; return false; }

    if (c == KEY_NEXT || c == KEY_RIGHT) {
      _scan_row = (_scan_row + 1) % n;
      scrollToScanRow();
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {   // double-click gives control back
      _scan_ctl = false;
      return true;
    }
    if (c == KEY_ENTER) {
      openSelectedRepeater();
      return true;
    }
    return true;   // swallow everything else while in control
  }

  // Modal popup over the Advert page, Meshtastic-style: opaque box, light
  // border, selected row inverted.
  void renderAdvertMenu(DisplayDriver& display) {
    static const char* items[ADVERT_MENU_ITEMS] = { "Back", "Flood advert", "Zero-hop advert" };
    uiRenderMenu(display, items, ADVERT_MENU_ITEMS, _advert_sel);
  }

  // returns true if the popup consumed the event
  bool handleAdvertMenuInput(char c) {
    if (c == KEY_NEXT || c == KEY_RIGHT) {          // single press -> down
      _advert_sel = (_advert_sel + 1) % ADVERT_MENU_ITEMS;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {           // double press -> up
      _advert_sel = (_advert_sel + ADVERT_MENU_ITEMS - 1) % ADVERT_MENU_ITEMS;
      return true;
    }
    if (c == KEY_ENTER) {                           // long press -> select
      int sel = _advert_sel;
      _advert_menu = false;                         // close in every case
      if (sel == 0) return true;                    // Back: do nothing

      bool flood = (sel == 1);
      _task->notify(UIEventType::ack);
      if (the_mesh.advert(flood)) {
        _task->showAlert(flood ? "Flood advert sent" : "Zero-hop advert sent", 1200);
      } else {
        _task->showAlert("Advert failed..", 1200);
      }
      return true;
    }
    return true;   // modal: swallow everything else
  }

  // Rows are rebuilt every render: one per contact that has sent us a direct
  // message (none shown if there are none), then every configured channel.
  int buildRows() {
    int n = 0;
    MsgStore& store = _task->msgs();

    char senders[UI_MAX_CHAN_ROWS][UI_MSG_SENDER_LEN];
    int nd = store.listDirectSenders(senders, UI_MAX_CHAN_ROWS);
    for (int i = 0; i < nd && n < UI_MAX_CHAN_ROWS; i++, n++) {
      _rows[n].key.channel_idx = DM_CHANNEL;
      strncpy(_rows[n].key.sender, senders[i], UI_MSG_SENDER_LEN - 1);
      _rows[n].key.sender[UI_MSG_SENDER_LEN - 1] = 0;
      strncpy(_rows[n].name, senders[i], UI_MSG_SENDER_LEN - 1);
      _rows[n].name[UI_MSG_SENDER_LEN - 1] = 0;
    }

#ifdef MAX_GROUP_CHANNELS
    for (int i = 0; i < MAX_GROUP_CHANNELS && n < UI_MAX_CHAN_ROWS; i++) {
      ChannelDetails cd;
      if (!the_mesh.getChannel(i, cd)) continue;
      if (cd.name[0] == 0) continue;   // unconfigured slot
      _rows[n].key.channel_idx = (int8_t) i;
      _rows[n].key.sender[0] = 0;
      strncpy(_rows[n].name, cd.name, UI_MSG_SENDER_LEN - 1);
      _rows[n].name[UI_MSG_SENDER_LEN - 1] = 0;
      n++;
    }
#endif
    _num_rows = n;
    return n;
  }

  void scrollToSelection() {
    if (_chan_sel < _chan_scroll) _chan_scroll = _chan_sel;
    if (_chan_sel >= _chan_scroll + CHAN_VISIBLE) _chan_scroll = _chan_sel - CHAN_VISIBLE + 1;
    if (_chan_scroll < 0) _chan_scroll = 0;
  }

  void renderChannels(DisplayDriver& display) {
    int n = buildRows();
    display.setTextSize(1);
    if (n == 0) {
      display.setColor(DisplayDriver::LIGHT);
      display.drawTextCentered(display.width() / 2, 30, "(no channels)");
      return;
    }
    if (_chan_sel >= n) _chan_sel = n - 1;
    scrollToSelection();

    MsgStore& store = _task->msgs();
    char tmp[UI_MSG_SENDER_LEN];
    char cnt[8];

    for (int i = 0; i < CHAN_VISIBLE; i++) {
      int r = _chan_scroll + i;
      if (r >= n) break;
      int y = CHAN_TOP_Y + i * CHAN_ROW_H;
      bool selected = _chan_control && (r == _chan_sel);

      int unread = store.unreadFor(_rows[r].key);
      cnt[0] = 0;
      if (unread > 0) sprintf(cnt, "%d", unread);
      int cnt_w = cnt[0] ? display.getTextWidth(cnt) : 0;

      if (selected) {   // inverted row: fill light, draw text dark
        display.setColor(DisplayDriver::LIGHT);
        display.fillRect(0, y - 1, display.width(), CHAN_ROW_H);
        display.setColor(DisplayDriver::DARK);
      } else {
        display.setColor(DisplayDriver::LIGHT);
      }

      display.translateUTF8ToBlocks(tmp, _rows[r].name, sizeof(tmp));
      display.drawTextEllipsized(1, y, display.width() - cnt_w - 4, tmp);
      if (cnt[0]) {
        display.setCursor(display.width() - cnt_w - 1, y);
        display.print(cnt);
      }
    }

    // scrollbar hint when the list overflows
    if (n > CHAN_VISIBLE) {
      display.setColor(DisplayDriver::LIGHT);
      int track_h = CHAN_VISIBLE * CHAN_ROW_H;
      int knob_h = track_h * CHAN_VISIBLE / n;
      if (knob_h < 3) knob_h = 3;
      int knob_y = CHAN_TOP_Y - 1 + (track_h - knob_h) * _chan_scroll / (n - CHAN_VISIBLE);
      display.fillRect(display.width() - 1, knob_y, 1, knob_h);
    }
  }

  bool handleChannelsInput(char c) {
    if (!_chan_control) {
      if (c == KEY_ENTER) {          // long-press: take control of the list
        if (buildRows() == 0) return false;
        _chan_control = true;
        _chan_sel = 0;
        _chan_scroll = 0;
        return true;
      }
      return false;                  // let page navigation handle it
    }
    // has control
    if (c == KEY_NEXT || c == KEY_RIGHT) {
      if (_num_rows > 0) _chan_sel = (_chan_sel + 1) % _num_rows;
      scrollToSelection();
      return true;
    }
    if (c == KEY_ENTER) {            // long-press again: give control back
      _chan_control = false;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {   // double-click: open the channel
      if (_num_rows > 0) {
        _task->openChannelView(_rows[_chan_sel].key, _rows[_chan_sel].name);
      }
      return true;
    }
    return true;   // swallow everything else while in control
  }


#ifndef BATT_MIN_MILLIVOLTS
  #define BATT_MIN_MILLIVOLTS 3000
#endif
#ifndef BATT_MAX_MILLIVOLTS
  #define BATT_MAX_MILLIVOLTS 4200
#endif

  static int battPercent(uint16_t milliVolts) {
    int pc = ((milliVolts - BATT_MIN_MILLIVOLTS) * 100) / (BATT_MAX_MILLIVOLTS - BATT_MIN_MILLIVOLTS);
    if (pc < 0) pc = 0;
    if (pc > 100) pc = 100;
    return pc;
  }

  static const char* pageTitle(uint8_t page) {
    switch (page) {
      case HomePage::FIRST:     return "Home";
      case HomePage::CHANNELS:  return "Channels";
      case HomePage::RECENT:    return "Recent";
      case HomePage::SCAN:      return "Scan";
      case HomePage::RADIO:     return "Radio";
      case HomePage::BLUETOOTH: return "BT";
      case HomePage::ADVERT:    return "Advert";
#if ENV_INCLUDE_GPS == 1
      case HomePage::GPS:       return "GPS";
#endif
#if UI_SENSORS_PAGE == 1
      case HomePage::SENSORS:   return "Sensors";
#endif
      case HomePage::SHUTDOWN:  return "Power";
    }
    return "";
  }

  // Top status bar: battery icon + percent (left), page title (centre),
  // bluetooth glyph and clock (right), with a separator line underneath.
  void renderTopBar(DisplayDriver& display) {
    char tmp[16];

    // --- left: battery. Kept tight so "Channels" fits the centre slot. ---
    const int battW = 10, battH = 8, battY = 2;
    int pc = battPercent(_task->getBattMilliVolts());
    display.setColor(DisplayDriver::GREEN);
    display.drawRect(0, battY, battW, battH);
    display.fillRect(battW, battY + 2, 2, battH - 4);            // cap

    if (_task->isCharging()) {
      // a bolt inside the outline, instead of the level bar. Costs no width, so
      // the centre title keeps its room.
      display.drawXbm(1, battY + 1, charge_bolt, 8, 6);
    } else {
      int fill = ((battW - 4) * pc) / 100;
      if (fill > 0) display.fillRect(2, battY + 2, fill, battH - 4);
    }

    display.setTextSize(1);
    const int bars = _task->signalBars();
    const int bars_w = bars > 0 ? 7 : 0;         // 2px gap + three 1px bars, 1px apart

    // How much room the right-hand cluster leaves us. The bluetooth glyph only
    // appears once a peer is connected.
    const char* title = pageTitle(_page);
    uint32_t now = _rtc->getCurrentTime();
    char clock_str[12];
    if (now < 1000000000UL) strcpy(clock_str, "--:--");
    else sprintf(clock_str, "%02d:%02d", (int)((now / 3600) % 24), (int)((now / 60) % 60));
    const int clock_x = display.width() - display.getTextWidth(clock_str);
    const int right_start = _task->hasConnection() ? (clock_x - 2 - 8 - 1) : clock_x;

    // Prefer "87%", but drop the '%' when the page title (e.g. "Channels")
    // would otherwise be ellipsized. Only that page pays the cost.
    sprintf(tmp, "%d%%", pc);
    int pct_w = display.getTextWidth(tmp);
    if (display.getTextWidth(title) > right_start - (battW + 3 + pct_w + bars_w) - 2) {
      sprintf(tmp, "%d", pc);
      pct_w = display.getTextWidth(tmp);
    }
    display.setCursor(battW + 3, battY);
    display.print(tmp);
    int left_end = battW + 3 + pct_w;

    // signal bars: any packet or scan reply in the last 15 min. Taller = more
    // recent (3 = within 5 min, 2 = within 10, 1 = within 15).
    if (bars > 0) {
      const int sx = left_end + 2;
      const int bottom = battY + battH - 1;
      display.setColor(DisplayDriver::LIGHT);
      for (int b = 0; b < bars; b++) {
        int h = 2 + b * 2;                       // 2, 4, 6
        display.fillRect(sx + b * 2, bottom - h + 1, 1, h);
      }
      left_end = sx + 5;
    }

#ifdef PIN_BUZZER
    if (_task->isBuzzerQuiet()) {
      display.setColor(DisplayDriver::RED);
      display.drawXbm(left_end + 2, battY, muted_icon, 8, 8);
      left_end += 10;
    }
#endif

    // --- right: clock (already formatted above), then bluetooth glyph ---
    display.setColor(DisplayDriver::LIGHT);
    display.setCursor(clock_x, battY);
    display.print(clock_str);

    // bluetooth glyph appears only once a peer is actually connected
    if (_task->hasConnection()) {
      display.drawXbm(clock_x - 2 - 8, battY, topbar_bt_icon, 8, 8);
    }

    // --- centre: page title, ellipsized into whatever room is left ---
    display.setColor(DisplayDriver::LIGHT);
    int avail = right_start - left_end - 2;
    if (avail > 8) {
      int tw = display.getTextWidth(title);
      int tx = (display.width() - tw) / 2;
      if (tx < left_end + 2) tx = left_end + 2;
      if (tw > avail) {
        display.drawTextEllipsized(left_end + 2, battY, avail, title);
      } else {
        display.setCursor(tx, battY);
        display.print(title);
      }
    }

    // --- separator ---
    display.setColor(DisplayDriver::LIGHT);
    display.fillRect(0, UI_TOPBAR_SEP_Y, display.width(), 1);
  }

  // Bottom nav bar: one 8x8 icon per page, current page boxed. Drawn last so it
  // overlays page content, on an opaque strip like Meshtastic's.
  void renderNavBar(DisplayDriver& display) {
    static const uint8_t* icons[] = {
      nav_home_icon,
      nav_chan_icon,
      nav_recent_icon,
      nav_scan_icon,
      nav_radio_icon,
      nav_bt_icon,
      nav_advert_icon,
#if ENV_INCLUDE_GPS == 1
      nav_radio_icon,
#endif
#if UI_SENSORS_PAGE == 1
      nav_radio_icon,
#endif
      nav_power_icon,
    };
    static_assert(sizeof(icons) / sizeof(icons[0]) == (size_t)HomePage::Count,
                  "nav bar icon count must match HomePage::Count");

    const int n = HomePage::Count;
    const int total = n * UI_NAV_ICON_SIZE + (n - 1) * UI_NAV_ICON_GAP;
    int x = (display.width() - total) / 2;
    const int y = display.height() - UI_NAV_ICON_SIZE - 1;

    // opaque strip so page content doesn't bleed through
    display.setColor(DisplayDriver::DARK);
    display.fillRect(0, y - 2, display.width(), display.height() - y + 2);

    display.setColor(DisplayDriver::LIGHT);
    for (int i = 0; i < n; i++, x += UI_NAV_ICON_SIZE + UI_NAV_ICON_GAP) {
      display.drawXbm(x, y, icons[i], UI_NAV_ICON_SIZE, UI_NAV_ICON_SIZE);
      if (i == _page) {   // drawXbm always writes white, so box the active icon
        display.drawRect(x - 2, y - 2, UI_NAV_ICON_SIZE + 4, UI_NAV_ICON_SIZE + 3);
      }
    }
  }

#if ENV_INCLUDE_GPS == 1
  // GPS is "on" only if software-enabled and, where present, the hardware
  // switch agrees
  bool gpsEnabled() {
    if (!_task->getGPSState()) return false;
#ifdef PIN_GPS_SWITCH
    if (!digitalRead(PIN_GPS_SWITCH)) return false;
#endif
    return true;
  }

  // 32x32 satellite, centred; 'disabled' strikes it through
  void renderSatIcon(DisplayDriver& display, bool disabled) {
    const int x = (display.width() - 32) / 2, y = 13;
    display.setColor(DisplayDriver::GREEN);
    display.drawXbm(x, y, sat_icon, 32, 32);
    if (disabled) {
      display.setColor(DisplayDriver::RED);
      uiDrawLine(display, x + 2, y + 2, x + 29, y + 29);
      uiDrawLine(display, x + 2, y + 3, x + 28, y + 29);   // 2px thick
    }
  }

  void renderGpsSearching(DisplayDriver& display, GNSSSkyView* sky) {
    renderSatIcon(display, false);

    int pc = sky ? sky->acquisitionPercent() : 0;
    const int bx = 20, by = 47, bw = 88, bh = 6;
    display.setColor(DisplayDriver::LIGHT);
    display.drawRect(bx, by, bw, bh);
    int fill = ((bw - 2) * pc) / 100;
    if (fill > 0) display.fillRect(bx + 1, by + 1, fill, bh - 2);
  }

  void renderGpsDisabled(DisplayDriver& display) {
    renderSatIcon(display, true);
    display.setColor(DisplayDriver::LIGHT);
    display.setTextSize(1);
    display.drawTextCentered(display.width() / 2, 47, "GPS Disabled");
  }

  // left: polar sky map (north up). right: sats / position / altitude.
  void renderGpsFixed(DisplayDriver& display, LocationProvider* nmea, GNSSSkyView* sky) {
    const int cx = 28, cy = 38, r = 21;
    unsigned long now = millis();

    display.setColor(DisplayDriver::LIGHT);
    uiDrawCircle(display, cx, cy, r);        // horizon (elevation 0)
    uiDrawCircle(display, cx, cy, r / 2);    // elevation 45
    display.fillRect(cx, cy - 1, 1, 3);      // centre tick (zenith)

    if (sky) {
      for (int i = 0; i < sky->count(); i++) {
        const SatInfo& s = sky->sat(i);
        if (s.snr == 0 && s.elev == 0 && s.azim == 0) continue;   // nothing known yet
        float rad = (float)r * (90.0f - (float)s.elev) / 90.0f;
        float a = (float)s.azim * (float)M_PI / 180.0f;
        int sx = cx + (int)lroundf(rad * sinf(a));
        int sy = cy - (int)lroundf(rad * cosf(a));
        if (sky->isUsed(s, now)) {
          display.fillRect(sx - 1, sy - 1, 3, 3);      // used in the fix
        } else {
          display.drawRect(sx - 1, sy - 1, 3, 3);      // in view only
        }
      }
    }

    // right-hand column
    char buf[24];
    const int tx = 56;
    display.setTextSize(1);
    display.setColor(DisplayDriver::GREEN);
    sprintf(buf, "SAT: %d", (int)nmea->satellitesCount());
    display.drawTextEllipsized(tx, 13, display.width() - tx, buf);

    display.setColor(DisplayDriver::LIGHT);
    display.drawTextEllipsized(tx, 21, display.width() - tx, "Position:");
    sprintf(buf, "%.5f", nmea->getLatitude() / 1000000.0);
    display.drawTextEllipsized(tx, 29, display.width() - tx, buf);
    sprintf(buf, "%.5f", nmea->getLongitude() / 1000000.0);
    display.drawTextEllipsized(tx, 37, display.width() - tx, buf);

    display.drawTextEllipsized(tx, 45, display.width() - tx, "Altitude:");
    sprintf(buf, "%.1fm", nmea->getAltitude() / 1000.0);
    display.drawTextEllipsized(tx, 53, display.width() - tx, buf);
  }
#endif  // ENV_INCLUDE_GPS

  // true while the nav bar should be on screen
  bool navBarVisible() const {
#if UI_NAVBAR_MILLIS == 0
    return true;
#else
    return (millis() - _page_changed_at) <= (unsigned long)UI_NAVBAR_MILLIS;
#endif
  }

  CayenneLPP sensors_lpp;
  int sensors_nb = 0;
  bool sensors_scroll = false;
  int sensors_scroll_offset = 0;
  int next_sensors_refresh = 0;

  void refresh_sensors() {
    if (millis() > next_sensors_refresh) {
      sensors_lpp.reset();
      sensors_nb = 0;
      sensors_lpp.addVoltage(TELEM_CHANNEL_SELF, (float)board.getBattMilliVolts() / 1000.0f);
      sensors.querySensors(0xFF, sensors_lpp);
      LPPReader reader (sensors_lpp.getBuffer(), sensors_lpp.getSize());
      uint8_t channel, type;
      while(reader.readHeader(channel, type)) {
        reader.skipData(type);
        sensors_nb ++;
      }
      sensors_scroll = sensors_nb > UI_RECENT_LIST_SIZE;
#if AUTO_OFF_MILLIS > 0
      next_sensors_refresh = millis() + 5000; // refresh sensor values every 5 sec
#else
      next_sensors_refresh = millis() + 60000; // refresh sensor values every 1 min
#endif
    }
  }

public:
  HomeScreen(UITask* task, mesh::RTCClock* rtc, SensorManager* sensors, NodePrefs* node_prefs)
     : _task(task), _rtc(rtc), _sensors(sensors), _node_prefs(node_prefs), _page(0),
       _shutdown_init(false), _page_changed_at(0), _first_render(true),
       _num_rows(0), _chan_control(false), _chan_sel(0), _chan_scroll(0),
       _advert_menu(false), _advert_sel(0), _scan_menu(false), _scan_sel(0),
       _scan_ctl(false), _scan_row(0), _scan_scroll(0), _rep_menu(false), _rep_sel(0),
       _rec_ctl(false), _rec_row(0), _rec_scroll(0), _rec_menu(false), _rec_sel(0),
       sensors_lpp(200) {  }

  // returning from a channel view: stay on the Channels page, keep control, and
  // re-select the row the user was reading
  void gotoChannelsPage(const MsgRowKey& key) {
    _page = HomePage::CHANNELS;
    _page_changed_at = millis();
    _advert_menu = false;
    _scan_menu = _rep_menu = _rec_menu = false;
    _scan_ctl = _rec_ctl = false;
    _chan_control = true;
    int n = buildRows();
    _chan_sel = 0;
    for (int i = 0; i < n; i++) {
      if (_rows[i].key.equals(key)) { _chan_sel = i; break; }
    }
    scrollToSelection();
  }

  // returning from a path / info screen: stay on Recent, keep control
  void gotoRecentPage() {
    _page = HomePage::RECENT;
    _page_changed_at = millis();
    _rec_menu = false;
    _rec_ctl = true;
  }

  // returning from the node-info screen: stay on Scan, keep Inspect control
  void gotoScanPage() {
    _page = HomePage::SCAN;
    _page_changed_at = millis();
    _scan_menu = _rep_menu = false;
    _scan_ctl = true;
    scrollToScanRow();
  }

  void gotoBluetoothPage() {
    _page = HomePage::BLUETOOTH;
    _page_changed_at = millis();
    _advert_menu = false;   // a pairing jump must not strand the modal open
    _scan_menu = _rep_menu = _rec_menu = false;
    _scan_ctl = _rec_ctl = false;
    _chan_control = false;
  }

  void poll() override {
    if (_shutdown_init && !_task->isButtonPressed()) {  // must wait for USR button to be released
      _task->shutdown();
    }
  }

  int render(DisplayDriver& display) override {
    char tmp[80];

    if (_first_render) {   // show the nav bar briefly when Home first appears
      _first_render = false;
      _page_changed_at = millis();
    }

    renderTopBar(display);

    if (_page == HomePage::FIRST) {
      // node name, unread count, battery voltage -- nothing else.
      // Voltage sits top-right on the name's line, or drops onto its own line
      // underneath when the name needs the full width.
      const int NAME_Y = 16;
      display.setTextSize(1);

      char volts[12];
      uint16_t mv = _task->getBattMilliVolts();
      sprintf(volts, "%d.%02dV", mv / 1000, (mv % 1000) / 10);
      int volts_w = display.getTextWidth(volts);

      char filtered_name[sizeof(_node_prefs->node_name)];
      display.translateUTF8ToBlocks(filtered_name, _node_prefs->node_name, sizeof(filtered_name));
      int name_w = display.getTextWidth(filtered_name);

      bool same_line = (name_w <= display.width() - volts_w - 4);
      int volts_y = same_line ? NAME_Y : NAME_Y + 10;

      display.setColor(DisplayDriver::GREEN);
      display.drawTextEllipsized(0, NAME_Y, same_line ? display.width() - volts_w - 4
                                                      : display.width(), filtered_name);

      display.setColor(DisplayDriver::LIGHT);
      display.drawTextRightAlign(display.width(), volts_y, volts);

      // push the unread count below whichever header layout we used
      display.setColor(DisplayDriver::YELLOW);
      display.setTextSize(2);
      sprintf(tmp, "MSG: %d", _task->getMsgCount());
      display.drawTextCentered(display.width() / 2, same_line ? 30 : 37, tmp);

      #ifdef WIFI_SSID
        IPAddress ip = WiFi.localIP();
        snprintf(tmp, sizeof(tmp), "IP: %d.%d.%d.%d", ip[0], ip[1], ip[2], ip[3]);
        display.setTextSize(1);
        display.drawTextCentered(display.width() / 2, 54, tmp);
      #endif
    } else if (_page == HomePage::CHANNELS) {
      renderChannels(display);
    } else if (_page == HomePage::SCAN) {
      renderScan(display);
    } else if (_page == HomePage::RECENT) {
      const int n = the_mesh.getRecentlyHeardCount();   // sorts newest-first
      display.setTextSize(1);
      if (n == 0) {
        display.setColor(DisplayDriver::LIGHT);
        display.drawTextCentered(display.width() / 2, 30, "(nothing heard)");
      } else {
        if (_rec_row >= n) _rec_row = n - 1;
        if (_rec_scroll > n - 1) _rec_scroll = n - 1;
        if (_rec_scroll < 0) _rec_scroll = 0;

        for (int i = 0; i < CHAN_VISIBLE; i++) {
          int idx = _rec_scroll + i;
          if (idx >= n) break;
          const AdvertPath* a = the_mesh.getRecentlyHeardAt(idx);
          if (a == NULL) break;
          int y = CHAN_TOP_Y + i * CHAN_ROW_H;
          bool selected = _rec_ctl && (idx == _rec_row);

          int secs = _rtc->getCurrentTime() - a->recv_timestamp;
          if (secs < 0) secs = 0;
          if (secs < 60)        sprintf(tmp, "%ds", secs);
          else if (secs < 3600) sprintf(tmp, "%dm", secs / 60);
          else                  sprintf(tmp, "%dh", secs / 3600);
          int age_w = display.getTextWidth(tmp);

          if (selected) {
            display.setColor(DisplayDriver::LIGHT);
            display.fillRect(0, y - 1, display.width(), CHAN_ROW_H);
            display.setColor(DisplayDriver::DARK);
          } else {
            display.setColor(DisplayDriver::GREEN);
          }
          char filtered_recent_name[sizeof(a->name)];
          display.translateUTF8ToBlocks(filtered_recent_name, a->name, sizeof(filtered_recent_name));
          display.drawTextEllipsized(1, y, display.width() - age_w - 4, filtered_recent_name);
          display.setCursor(display.width() - age_w - 1, y);
          display.print(tmp);
        }

        if (n > CHAN_VISIBLE) {
          display.setColor(DisplayDriver::LIGHT);
          int track_h = CHAN_VISIBLE * CHAN_ROW_H;
          int knob_h = track_h * CHAN_VISIBLE / n; if (knob_h < 3) knob_h = 3;
          int knob_y = CHAN_TOP_Y - 1 + (track_h - knob_h) * _rec_scroll / (n - CHAN_VISIBLE);
          display.fillRect(display.width() - 1, knob_y, 1, knob_h);
        }
      }
    } else if (_page == HomePage::RADIO) {
      display.setColor(DisplayDriver::YELLOW);
      display.setTextSize(1);
      // freq / sf
      display.setCursor(0, 20);
      sprintf(tmp, "FQ: %06.3f   SF: %d", _node_prefs->freq, _node_prefs->sf);
      display.print(tmp);

      display.setCursor(0, 31);
      sprintf(tmp, "BW: %03.2f     CR: %d", _node_prefs->bw, _node_prefs->cr);
      display.print(tmp);

      // tx power,  noise floor
      display.setCursor(0, 42);
      sprintf(tmp, "TX: %ddBm", _node_prefs->tx_power_dbm);
      display.print(tmp);
      display.setCursor(0, 53);
      sprintf(tmp, "Noise floor: %d", radio_driver.getNoiseFloor());
      display.print(tmp);
    } else if (_page == HomePage::BLUETOOTH) {
      display.setTextSize(1);
      if (!_task->isSerialEnabled()) {
        display.setColor(DisplayDriver::LIGHT);
        display.drawTextCentered(display.width() / 2, 26, "Bluetooth turned off");
      } else if (_task->hasConnection()) {
        // link quality, drawn like the IN/OUT meters on the node-info screen
        const int METER_W = 18;
        int rssi = _task->connectionRssi();

        display.setColor(DisplayDriver::GREEN);
        display.drawTextCentered(display.width() / 2, 15, "Connected");

        display.setColor(DisplayDriver::LIGHT);
        uiDrawSignalMeter(display, (display.width() - METER_W) / 2, 26, rssiToBars(rssi));
        if (rssi != 0) {          // 0 = no reading back from the stack yet
          sprintf(tmp, "%d dBm", rssi);
        } else {
          strcpy(tmp, "--");
        }
        display.drawTextCentered(display.width() / 2, 41, tmp);
      } else if (_task->isPairing() && the_mesh.getBLEPin() != 0) {
        // a peer is pairing right now and needs the PIN
        display.setColor(DisplayDriver::LIGHT);
        display.drawTextCentered(display.width() / 2, 20, "Pairing");
        display.setColor(DisplayDriver::RED);
        display.setTextSize(2);
        sprintf(tmp, "%d", the_mesh.getBLEPin());
        display.drawTextCentered(display.width() / 2, 32, tmp);
        display.setTextSize(1);
      } else {
        display.setColor(DisplayDriver::LIGHT);
        display.drawTextCentered(display.width() / 2, 26, "Disconnected");
      }
      display.setColor(DisplayDriver::LIGHT);
      display.drawTextCentered(display.width() / 2, 64 - 11, "toggle: " PRESS_LABEL);
    } else if (_page == HomePage::ADVERT) {
      display.setColor(DisplayDriver::GREEN);
      display.drawXbm((display.width() - 32) / 2, 18, advert_icon, 32, 32);
      display.drawTextCentered(display.width() / 2, 64 - 11, "menu: " PRESS_LABEL);
#if ENV_INCLUDE_GPS == 1
    } else if (_page == HomePage::GPS) {
      LocationProvider* nmea = sensors.getLocationProvider();
      if (nmea == NULL) {
        display.setColor(DisplayDriver::LIGHT);
        display.setTextSize(1);
        display.drawTextCentered(display.width() / 2, 30, "Can't access GPS");
      } else if (!gpsEnabled()) {
        renderGpsDisabled(display);
      } else if (nmea->isValid()) {
        renderGpsFixed(display, nmea, nmea->getSkyView());
      } else {
        renderGpsSearching(display, nmea->getSkyView());
      }
#endif
#if UI_SENSORS_PAGE == 1
    } else if (_page == HomePage::SENSORS) {
      int y = 18;
      refresh_sensors();
      char buf[30];
      char name[30];
      LPPReader r(sensors_lpp.getBuffer(), sensors_lpp.getSize());

      for (int i = 0; i < sensors_scroll_offset; i++) {
        uint8_t channel, type;
        r.readHeader(channel, type);
        r.skipData(type);
      }

      for (int i = 0; i < (sensors_scroll?UI_RECENT_LIST_SIZE:sensors_nb); i++) {
        uint8_t channel, type;
        if (!r.readHeader(channel, type)) { // reached end, reset
          r.reset();
          r.readHeader(channel, type);
        }

        display.setCursor(0, y);
        float v;
        switch (type) {
          case LPP_GPS: // GPS
            float lat, lon, alt;
            r.readGPS(lat, lon, alt);
            strcpy(name, "gps"); sprintf(buf, "%.4f %.4f", lat, lon);
            break;
          case LPP_VOLTAGE:
            r.readVoltage(v);
            strcpy(name, "voltage"); sprintf(buf, "%6.2f", v);
            break;
          case LPP_CURRENT:
            r.readCurrent(v);
            strcpy(name, "current"); sprintf(buf, "%.3f", v);
            break;
          case LPP_TEMPERATURE:
            r.readTemperature(v);
            strcpy(name, "temperature"); sprintf(buf, "%.2f", v);
            break;
          case LPP_RELATIVE_HUMIDITY:
            r.readRelativeHumidity(v);
            strcpy(name, "humidity"); sprintf(buf, "%.2f", v);
            break;
          case LPP_BAROMETRIC_PRESSURE:
            r.readPressure(v);
            strcpy(name, "pressure"); sprintf(buf, "%.2f", v);
            break;
          case LPP_ALTITUDE:
            r.readAltitude(v);
            strcpy(name, "altitude"); sprintf(buf, "%.0f", v);
            break;
          case LPP_POWER:
            r.readPower(v);
            strcpy(name, "power"); sprintf(buf, "%6.2f", v);
            break;
          default:
            r.skipData(type);
            strcpy(name, "unk"); sprintf(buf, "");
        }
        display.setCursor(0, y);
        display.print(name);
        display.setCursor(
          display.width()-display.getTextWidth(buf)-1, y
        );
        display.print(buf);
        y = y + 12;
      }
      if (sensors_scroll) sensors_scroll_offset = (sensors_scroll_offset+1)%sensors_nb;
      else sensors_scroll_offset = 0;
#endif
    } else if (_page == HomePage::SHUTDOWN) {
      display.setColor(DisplayDriver::GREEN);
      display.setTextSize(1);
      if (_shutdown_init) {
        display.drawTextCentered(display.width() / 2, 34, "hibernating...");
      } else {
        display.drawXbm((display.width() - 32) / 2, 18, power_icon, 32, 32);
        display.drawTextCentered(display.width() / 2, 64 - 11, "hibernate:" PRESS_LABEL);
      }
    }

    // the GPS page animates (progress bar, sky map), so it refreshes faster.
    // Bluetooth tracks the live link RSSI, which is re-read every 2s.
    int page_delay = 5000;
    if (_page == HomePage::SCAN || _page == HomePage::RECENT) page_delay = 1000;
    if (_page == HomePage::BLUETOOTH && _task->hasConnection()) page_delay = 1000;
#if ENV_INCLUDE_GPS == 1
    if (_page == HomePage::GPS) page_delay = 1000;
#endif

    // popups are modal, so they hide the nav bar rather than fighting it
    if (_advert_menu) {
      renderAdvertMenu(display);
      return page_delay;
    }
    if (_scan_menu) {
      renderScanMenu(display);
      return page_delay;
    }
    if (_rep_menu) {
      renderRepMenu(display);
      return page_delay;
    }
    if (_rec_menu) {
      renderRecMenu(display);
      return page_delay;
    }

    // drawn last so it overlays the page content
    if (navBarVisible()) {
      renderNavBar(display);
#if UI_NAVBAR_MILLIS > 0
      // come back exactly when the bar is due to disappear
      unsigned long shown_for = millis() - _page_changed_at;
      long remaining = (long)UI_NAVBAR_MILLIS - (long)shown_for + 50;
      if (remaining < 50) remaining = 50;
      return remaining < page_delay ? (int)remaining : page_delay;
#endif
    }
    return page_delay;
  }

  bool handleInput(char c) override {
    // modal popups get first refusal, before any page navigation
    if (_advert_menu) return handleAdvertMenuInput(c);
    if (_scan_menu) return handleScanMenuInput(c);
    if (_rep_menu) return handleRepMenuInput(c);
    if (_rec_menu) return handleRecMenuInput(c);

    // the Scan page swallows input once "Inspect" has taken control
    if (_page == HomePage::SCAN && _scan_ctl && handleScanCtlInput(c)) return true;

    // the Recent page: long-press takes control, then swallows input
    if (_page == HomePage::RECENT && handleRecentInput(c)) return true;

    // the Channels page swallows input once the user has "taken control"
    if (_page == HomePage::CHANNELS && handleChannelsInput(c)) return true;

    if (c == KEY_LEFT || c == KEY_PREV) {
      _page = (_page + HomePage::Count - 1) % HomePage::Count;
      _page_changed_at = millis();
      return true;
    }
    if (c == KEY_NEXT || c == KEY_RIGHT) {
      _page = (_page + 1) % HomePage::Count;
      _page_changed_at = millis();
      if (_page == HomePage::RECENT) {
        _task->showAlert("Recent adverts", 800);
      }
      return true;
    }
    if (c == KEY_ENTER && _page == HomePage::BLUETOOTH) {
      if (_task->isSerialEnabled()) {  // toggle Bluetooth on/off
        _task->disableSerial();
      } else {
        _task->enableSerial();
      }
      return true;
    }
    if (c == KEY_ENTER && _page == HomePage::ADVERT) {
      _advert_menu = true;      // long press opens the popup instead of sending
      _advert_sel = 0;
      return true;
    }
    if (c == KEY_ENTER && _page == HomePage::SCAN) {
      _scan_menu = true;
      _scan_sel = 0;
      return true;
    }
#if ENV_INCLUDE_GPS == 1
    if (c == KEY_ENTER && _page == HomePage::GPS) {
      // long press toggles GPS while searching or disabled; once fixed it does
      // nothing (so you can't accidentally drop a hard-won lock)
      LocationProvider* nmea = sensors.getLocationProvider();
      bool fixed = gpsEnabled() && nmea != NULL && nmea->isValid();
      if (!fixed) _task->toggleGPS();
      return true;
    }
#endif
#if UI_SENSORS_PAGE == 1
    if (c == KEY_ENTER && _page == HomePage::SENSORS) {
      _task->toggleGPS();
      next_sensors_refresh=0;
      return true;
    }
#endif
    if (c == KEY_ENTER && _page == HomePage::SHUTDOWN) {
      _shutdown_init = true;  // need to wait for button to be released
      return true;
    }
    return false;
  }
};

// Reading view for one row of the Channels list: a group channel, or the direct
// messages from one contact. Renders each message as a sender line above a
// boxed, word-wrapped body.
//
//   Reko
//   +------------------+
//   |hello there       |
//   +------------------+
//
// click = scroll down, double-click = scroll up, long-press = back.
class ChannelViewScreen : public UIScreen {
  UITask* _task;
  MsgRowKey _key;
  char _title[UI_MSG_SENDER_LEN];
  int _scroll;
  bool _stick_to_bottom;

  static const int LINE_H  = 10;
  static const int BODY_Y  = 14;
  static const int VISIBLE = 5;    // (64 - 14) / 10

  int bodyCharsPerLine(DisplayDriver& display) {
    int cw = display.getTextWidth("W");
    if (cw < 1) cw = 6;
    return (display.width() - 8) / cw;   // 3px inset each side, plus box border
  }

  // Total rendered lines for this row: per message, 1 sender + body lines + 1 spacer.
  int totalLines(DisplayDriver& display) {
    MsgStore& store = _task->msgs();
    int cols = bodyCharsPerLine(display);
    char lines[6][UI_MSG_TEXT_LEN];
    int total = 0;
    for (int i = 0; i < store.count(); i++) {
      const StoredMsg* m = store.at(i);
      if (!_key.matches(*m)) continue;
      total += 1 + msgWrapLines(m->text, lines, 6, cols) + 1;
    }
    return total;
  }

public:
  ChannelViewScreen(UITask* task) : _task(task), _scroll(0), _stick_to_bottom(true) {
    _key.channel_idx = DM_CHANNEL;
    _key.sender[0] = 0;
    _title[0] = 0;
  }

  void open(const MsgRowKey& key, const char* title) {
    _key = key;
    strncpy(_title, title ? title : "", sizeof(_title) - 1);
    _title[sizeof(_title) - 1] = 0;
    _scroll = 0;
    _stick_to_bottom = true;   // start at the newest message
  }

  const MsgRowKey& key() const { return _key; }

  int render(DisplayDriver& display) override {
    MsgStore& store = _task->msgs();
    const int cols = bodyCharsPerLine(display);
    const int total = totalLines(display);
    const int max_scroll = total > VISIBLE ? total - VISIBLE : 0;

    if (_stick_to_bottom) _scroll = max_scroll;
    if (_scroll > max_scroll) _scroll = max_scroll;
    if (_scroll < 0) _scroll = 0;

    // --- header ---
    display.setTextSize(1);
    display.setColor(DisplayDriver::LIGHT);
    char filtered[UI_MSG_SENDER_LEN];
    display.translateUTF8ToBlocks(filtered, _title, sizeof(filtered));
    display.drawTextEllipsized(0, 2, display.width() - 30, filtered);
    if (total > VISIBLE) {   // scroll position, e.g. "2/7"
      char pos[12];
      sprintf(pos, "%d/%d", _scroll + 1, max_scroll + 1);
      display.drawTextRightAlign(display.width(), 2, pos);
    }
    display.fillRect(0, UI_TOPBAR_SEP_Y, display.width(), 1);

    if (store.countFor(_key) == 0) {
      display.drawTextCentered(display.width() / 2, 30, "(no messages)");
      return 2000;
    }

    // --- messages ---
    char lines[6][UI_MSG_TEXT_LEN];
    char tmp[UI_MSG_TEXT_LEN];
    int line = 0;   // global line index
    for (int i = 0; i < store.count(); i++) {
      const StoredMsg* m = store.at(i);
      if (!_key.matches(*m)) continue;

      int nbody = msgWrapLines(m->text, lines, 6, cols);

      // sender line
      if (line >= _scroll && line < _scroll + VISIBLE) {
        int y = BODY_Y + (line - _scroll) * LINE_H;
        display.setColor(DisplayDriver::YELLOW);
        display.translateUTF8ToBlocks(tmp, m->sender[0] ? m->sender : "(unknown)", sizeof(tmp));
        display.drawTextEllipsized(0, y, display.width(), tmp);
      }
      line++;

      // body lines, and a box clipped to whatever part is on screen
      int b0 = line, b1 = line + nbody - 1;
      display.setColor(DisplayDriver::LIGHT);
      for (int b = 0; b < nbody; b++, line++) {
        if (line < _scroll || line >= _scroll + VISIBLE) continue;
        int y = BODY_Y + (line - _scroll) * LINE_H;
        display.translateUTF8ToBlocks(tmp, lines[b], sizeof(tmp));
        display.setCursor(3, y);
        display.print(tmp);
      }
      int vis_top = b0 < _scroll ? _scroll : b0;
      int vis_bot = b1 > _scroll + VISIBLE - 1 ? _scroll + VISIBLE - 1 : b1;
      if (vis_top <= vis_bot) {
        int top_y = BODY_Y + (vis_top - _scroll) * LINE_H - 1;
        int bot_y = BODY_Y + (vis_bot - _scroll) * LINE_H + 8;
        if (bot_y > display.height() - 1) bot_y = display.height() - 1;
        display.drawRect(0, top_y, display.width(), bot_y - top_y + 1);
      }

      line++;   // spacer
      if (line >= _scroll + VISIBLE) break;   // rest is below the viewport
    }
    return 1000;
  }

  bool handleInput(char c) override {
    if (c == KEY_NEXT || c == KEY_RIGHT) {       // click -> scroll down
      _scroll++;
      _stick_to_bottom = false;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {        // double-click -> scroll up
      if (_scroll > 0) _scroll--;
      _stick_to_bottom = false;
      return true;
    }
    if (c == KEY_ENTER) {                        // long-press -> back
      _task->closeChannelView();
      return true;
    }
    return false;
  }
};

// Details for one node: its public key, and (for scan results) the two
// directions of the link as cellular-style meters. Long-press goes back.
class NodeInfoScreen : public UIScreen {
  UITask* _task;
  char _title[UI_MSG_SENDER_LEN];
  uint8_t _key[32];
  uint8_t _key_len;
  int8_t  _rx4, _tx4;
  bool    _has_tx;
  uint8_t _mode;

  static const int METER_W = 18;
  static const int LEFT_CX = 34, RIGHT_CX = 94;   // mirror about x=64

public:
  NodeInfoScreen(UITask* task) : _task(task) {
    memset(_key, 0, sizeof(_key));
    _key_len = 0; _rx4 = _tx4 = 0; _has_tx = false; _mode = NODEINFO_SIGNAL;
    _title[0] = 0;
  }

  void open(const char* title, const uint8_t* key, uint8_t key_len,
            int8_t rx4, int8_t tx4, bool has_tx, uint8_t mode) {
    strncpy(_title, title ? title : "", sizeof(_title) - 1);
    _title[sizeof(_title) - 1] = 0;
    if (key_len > sizeof(_key)) key_len = sizeof(_key);
    memcpy(_key, key, key_len);
    _key_len = key_len;
    _rx4 = rx4; _tx4 = tx4; _has_tx = has_tx; _mode = mode;
  }

  int render(DisplayDriver& display) override {
    char buf[40];
    display.setTextSize(1);
    display.setColor(DisplayDriver::LIGHT);

    char filtered[UI_MSG_SENDER_LEN];
    display.translateUTF8ToBlocks(filtered, _title, sizeof(filtered));
    display.drawTextCentered(display.width() / 2, 2, filtered);
    display.fillRect(0, UI_TOPBAR_SEP_Y, display.width(), 1);

    if (_mode == NODEINFO_FULLKEY) {
      // whole key, 16 hex chars (8 bytes) per line
      for (int line = 0, b = 0; b < _key_len && line < 5; line++, b += 8) {
        int n = _key_len - b; if (n > 8) n = 8;
        for (int i = 0; i < n; i++) sprintf(&buf[i * 2], "%02X", _key[b + i]);
        buf[n * 2] = 0;
        display.drawTextCentered(display.width() / 2, 15 + line * 10, buf);
      }
      return 5000;
    }

    // key prefix (8 bytes), then the two meters
    int n = _key_len < 8 ? _key_len : 8;
    for (int i = 0; i < n; i++) sprintf(&buf[i * 2], "%02X", _key[i]);
    buf[n * 2] = 0;
    display.drawTextCentered(display.width() / 2, 16, buf);

    const int my = 28;
    uiDrawSignalMeter(display, LEFT_CX  - METER_W / 2, my, snrToBars(_rx4));
    uiDrawSignalMeter(display, RIGHT_CX - METER_W / 2, my, _has_tx ? snrToBars(_tx4) : 0);

    display.drawTextCentered(LEFT_CX,  43, "IN");
    display.drawTextCentered(RIGHT_CX, 43, "OUT");

    sprintf(buf, "%ddB", _rx4 / 4);
    display.drawTextCentered(LEFT_CX, 53, buf);
    if (_has_tx) { sprintf(buf, "%ddB", _tx4 / 4); } else { strcpy(buf, "--"); }
    display.drawTextCentered(RIGHT_CX, 53, buf);
    return 5000;
  }

  bool handleInput(char c) override {
    if (c == KEY_ENTER) { _task->closeNodeInfo(); return true; }
    return true;   // modal
  }
};

// The hops an advert travelled. Each hop is a 1/2/3-byte prefix of a node's
// public key (path_len packs count in the low 6 bits, hash size in the top 2).
class PathScreen : public UIScreen {
  UITask* _task;
  uint8_t _path[MAX_PATH_SIZE];
  uint8_t _path_len;
  char _title[UI_MSG_SENDER_LEN];
  int _row, _scroll;

  static const int ROW_H = 10, TOP_Y = 15, VISIBLE = 4;

public:
  PathScreen(UITask* task) : _task(task), _path_len(0), _row(0), _scroll(0) { _title[0] = 0; }

  int hopCount() const { return _path_len & 63; }
  int hashSize() const { return (_path_len >> 6) + 1; }
  const uint8_t* hop(int i) const { return &_path[i * hashSize()]; }

  void open(const uint8_t* path, uint8_t path_len, const char* title) {
    _path_len = mesh::Packet::isValidPathLen(path_len) ? path_len : 0;   // reject junk
    if (_path_len) memcpy(_path, path, (size_t)(hopCount() * hashSize()));
    strncpy(_title, title ? title : "", sizeof(_title) - 1);
    _title[sizeof(_title) - 1] = 0;
    _row = _scroll = 0;
  }

  // how many contacts share this hop's key prefix, and the first one's name
  int matchesFor(int i, char* name, size_t name_sz) {
    const uint8_t* h = hop(i);
    const uint8_t hs = hashSize();
    int n = 0;
    for (int k = 0; k < the_mesh.getNumContacts(); k++) {
      ContactInfo c;
      if (!the_mesh.getContactByIdx(k, c)) continue;
      if (c.id.isHashMatch(h, hs)) {
        if (n == 0 && name) { strncpy(name, c.name, name_sz - 1); name[name_sz - 1] = 0; }
        n++;
      }
    }
    return n;
  }

  int render(DisplayDriver& display) override {
    display.setTextSize(1);
    display.setColor(DisplayDriver::LIGHT);
    char filtered[UI_MSG_SENDER_LEN];
    display.translateUTF8ToBlocks(filtered, _title, sizeof(filtered));
    display.drawTextEllipsized(0, 2, display.width() - 30, filtered);
    {
      char hdr[16];
      sprintf(hdr, "%dhop", hopCount());
      display.drawTextRightAlign(display.width(), 2, hdr);
    }
    display.fillRect(0, UI_TOPBAR_SEP_Y, display.width(), 1);

    const int n = hopCount();
    if (n == 0) {
      display.drawTextCentered(display.width() / 2, 30, "(direct, 0 hops)");
      return 5000;
    }
    if (_row >= n) _row = n - 1;
    if (_row < _scroll) _scroll = _row;
    if (_row >= _scroll + VISIBLE) _scroll = _row - VISIBLE + 1;

    char label[UI_MSG_SENDER_LEN];
    char name[32];
    for (int i = 0; i < VISIBLE; i++) {
      int idx = _scroll + i;
      if (idx >= n) break;
      int y = TOP_Y + i * ROW_H;

      int m = matchesFor(idx, name, sizeof(name));
      if (m == 0) {
        char hex[8]; const uint8_t* h = hop(idx);
        for (int b = 0; b < hashSize(); b++) sprintf(&hex[b * 2], "%02X", h[b]);
        hex[hashSize() * 2] = 0;
        snprintf(label, sizeof(label), "Unknown %s", hex);
      } else if (m == 1) {
        snprintf(label, sizeof(label), "%s", name);
      } else {
        snprintf(label, sizeof(label), "%s +%d", name, m - 1);
      }

      if (idx == _row) {
        display.setColor(DisplayDriver::LIGHT);
        display.fillRect(0, y - 1, display.width(), ROW_H);
        display.setColor(DisplayDriver::DARK);
      } else {
        display.setColor(DisplayDriver::LIGHT);
      }
      char f2[UI_MSG_SENDER_LEN];
      display.translateUTF8ToBlocks(f2, label, sizeof(f2));
      display.drawTextEllipsized(1, y, display.width() - 3, f2);
    }

    if (n > VISIBLE) {
      display.setColor(DisplayDriver::LIGHT);
      int track_h = VISIBLE * ROW_H;
      int knob_h = track_h * VISIBLE / n; if (knob_h < 3) knob_h = 3;
      int knob_y = TOP_Y - 1 + (track_h - knob_h) * _scroll / (n - VISIBLE);
      display.fillRect(display.width() - 1, knob_y, 1, knob_h);
    }
    return 5000;
  }

  bool handleInput(char c) override {
    const int n = hopCount();
    if (c == KEY_NEXT || c == KEY_RIGHT) {
      if (n > 0) _row = (_row + 1) % n;
      return true;
    }
    if (c == KEY_PREV || c == KEY_LEFT) {   // double-click leaves the path view
      _task->closePathView();
      return true;
    }
    if (c == KEY_ENTER) {
      if (n == 0) { _task->closePathView(); return true; }
      if (matchesFor(_row, NULL, 0) == 0) {
        _task->showAlert("Unknown repeater", 1200);
      } else {
        _task->openMatches(hop(_row), hashSize());
      }
      return true;
    }
    return true;
  }
};

// Every contact whose key starts with the selected hop's hash, listed with its
// full public key. Several nodes can share a short prefix, so all are shown.
class MatchesScreen : public UIScreen {
  UITask* _task;
  uint8_t _hash[3];
  uint8_t _hash_len;
  int _scroll;

  static const int LINE_H = 10, BODY_Y = 14, VISIBLE = 5;

  int totalLines() {
    int lines = 0;
    for (int k = 0; k < the_mesh.getNumContacts(); k++) {
      ContactInfo c;
      if (!the_mesh.getContactByIdx(k, c)) continue;
      if (!c.id.isHashMatch(_hash, _hash_len)) continue;
      lines += 1 + 4 + 1;   // name + 4 key lines + spacer
    }
    return lines;
  }

public:
  MatchesScreen(UITask* task) : _task(task), _hash_len(0), _scroll(0) { }

  void open(const uint8_t* hash, uint8_t hash_len) {
    if (hash_len > sizeof(_hash)) hash_len = sizeof(_hash);
    memcpy(_hash, hash, hash_len);
    _hash_len = hash_len;
    _scroll = 0;
  }

  int render(DisplayDriver& display) override {
    char buf[24];
    display.setTextSize(1);
    display.setColor(DisplayDriver::LIGHT);

    char hex[8];
    for (int b = 0; b < _hash_len; b++) sprintf(&hex[b * 2], "%02X", _hash[b]);
    hex[_hash_len * 2] = 0;
    sprintf(buf, "hash %s", hex);
    display.drawTextCentered(display.width() / 2, 2, buf);
    display.fillRect(0, UI_TOPBAR_SEP_Y, display.width(), 1);

    const int total = totalLines();
    const int max_scroll = total > VISIBLE ? total - VISIBLE : 0;
    if (_scroll > max_scroll) _scroll = max_scroll;
    if (_scroll < 0) _scroll = 0;

    int line = 0;
    for (int k = 0; k < the_mesh.getNumContacts(); k++) {
      ContactInfo c;
      if (!the_mesh.getContactByIdx(k, c)) continue;
      if (!c.id.isHashMatch(_hash, _hash_len)) continue;

      if (line >= _scroll && line < _scroll + VISIBLE) {
        int y = BODY_Y + (line - _scroll) * LINE_H;
        display.setColor(DisplayDriver::YELLOW);
        char f[UI_MSG_SENDER_LEN];
        display.translateUTF8ToBlocks(f, c.name[0] ? c.name : "(unnamed)", sizeof(f));
        display.drawTextEllipsized(0, y, display.width(), f);
      }
      line++;

      display.setColor(DisplayDriver::LIGHT);
      for (int b = 0; b < 4; b++, line++) {   // 32 bytes -> 4 lines of 16 hex
        if (line < _scroll || line >= _scroll + VISIBLE) continue;
        int y = BODY_Y + (line - _scroll) * LINE_H;
        for (int i = 0; i < 8; i++) sprintf(&buf[i * 2], "%02X", c.id.pub_key[b * 8 + i]);
        buf[16] = 0;
        display.drawTextCentered(display.width() / 2, y, buf);
      }
      line++;   // spacer
      if (line >= _scroll + VISIBLE) break;
    }
    return 5000;
  }

  bool handleInput(char c) override {
    if (c == KEY_NEXT || c == KEY_RIGHT) { _scroll++; return true; }
    if (c == KEY_PREV || c == KEY_LEFT)  { if (_scroll > 0) _scroll--; return true; }
    if (c == KEY_ENTER) { _task->closeMatches(); return true; }
    return true;
  }
};

void UITask::begin(DisplayDriver* display, SensorManager* sensors, NodePrefs* node_prefs) {
  _display = display;
  _sensors = sensors;
  _auto_off = millis() + AUTO_OFF_MILLIS;

#if defined(PIN_USER_BTN)
  user_btn.begin();
#endif
#if defined(PIN_USER_BTN_ANA)
  analog_btn.begin();
#endif

  _node_prefs = node_prefs;

  if (_display != NULL) {
    _display->turnOn();
  }

#ifdef PIN_BUZZER
  buzzer.begin();
  buzzer.quiet(_node_prefs->buzzer_quiet);
  buzzer.startup();
#endif

#ifdef PIN_VIBRATION
  vibration.begin();
#endif

  ui_started_at = millis();
  _alert_expiry = 0;

  splash = new SplashScreen(this);
  home = new HomeScreen(this, &rtc_clock, sensors, node_prefs);
  chan_view = new ChannelViewScreen(this);
  node_info = new NodeInfoScreen(this);
  path_view = new PathScreen(this);
  matches_view = new MatchesScreen(this);

#ifdef DEBUG_AUTOSCAN
  // diagnostic: arm autoscan at boot, so the scan path can be exercised without
  // a button press
  _autoscan = true;
  _pkts_at_last_scan = radio_driver.getPacketsRecv();
  _next_autoscan = millis() + 4000;
#endif

  setCurrScreen(splash);
}

void UITask::showAlert(const char* text, int duration_millis) {
  strcpy(_alert, text);
  _alert_expiry = millis() + duration_millis;
}

void UITask::notify(UIEventType t) {
#if defined(PIN_BUZZER)
switch(t){
  case UIEventType::contactMessage:
    // gemini's pick
    buzzer.play("MsgRcv3:d=4,o=6,b=200:32e,32g,32b,16c7");
    break;
  case UIEventType::channelMessage:
    buzzer.play("kerplop:d=16,o=6,b=120:32g#,32c#");
    break;
  case UIEventType::ack:
    buzzer.play("ack:d=32,o=8,b=120:c");
    break;
  case UIEventType::roomMessage:
  case UIEventType::newContactMessage:
  case UIEventType::none:
  default:
    break;
}
#endif

#ifdef PIN_VIBRATION
  // Trigger vibration for all UI events except none
  if (t != UIEventType::none) {
    vibration.trigger();
  }
#endif
}


void UITask::msgRead(int msgcount) {
  _msgcount = msgcount;
  if (msgcount == 0) {
    // the phone app drained the queue, so nothing is "new" on the device either
    _msgs.clearAllUnread();
  }
}

// Wake the screen for an incoming message, but never steal the current screen:
// arriving messages only bump unread counts in the Channels list.
void UITask::wakeForMsg() {
  if (_display != NULL) {
    if (!_display->isOn() && !hasConnection()) {
      _display->turnOn();
    }
    if (_display->isOn()) {
      _auto_off = millis() + AUTO_OFF_MILLIS;  // extend the auto-off timer
      _next_refresh = 100;  // trigger refresh
    }
  }
}

// Messages are retained on the device whether or not the phone is connected.
// The store is a 48-entry ring, so it holds the most recent traffic and never
// grows; a reboot loses it.
void UITask::newMsg(uint8_t path_len, const char* from_name, const char* text, int msgcount) {
  _msgcount = msgcount;
  _msgs.addDirectMsg(from_name, text, rtc_clock.getCurrentTime());
  wakeForMsg();
}

void UITask::newChannelMsg(uint8_t channel_idx, const char* channel_name, uint8_t path_len,
                           const char* text, int msgcount) {
  _msgcount = msgcount;
  _msgs.addChannelMsg((int8_t) channel_idx, text, rtc_clock.getCurrentTime());
  wakeForMsg();
}

// ---------------- node scan ----------------

void UITask::startScan() {
  _scan_hits = 0;
  _scan_deadline = millis() + UI_SCAN_WINDOW_MILLIS;
  if (!the_mesh.discoverNearby()) {
    _scan_deadline = 0;
    showAlert("Scan failed..", 1000);
    return;
  }
  _next_refresh = 100;
}

void UITask::toggleAutoscan() {
  _autoscan = !_autoscan;
  if (_autoscan) {
    _pkts_at_last_scan = radio_driver.getPacketsRecv();
    _next_autoscan = millis();   // kick one off promptly
  }
}

// Replies arrive over ~1s after the request; keep the newest first and merge
// repeat answers from the same node rather than duplicating a row.
void UITask::nodeDiscovered(uint8_t node_type, int8_t rx_snr4, int8_t tx_snr4,
                            const uint8_t* pub_key, uint8_t key_len) {
  if (key_len > UI_SCAN_KEY_LEN) key_len = UI_SCAN_KEY_LEN;
  if (key_len == 0) return;
  _scan_hits++;

  int slot = -1;
  for (int i = 0; i < _scan_count; i++) {
    if (_scan[i].key_len == key_len && memcmp(_scan[i].key, pub_key, key_len) == 0) { slot = i; break; }
  }
  if (slot < 0) {
    if (_scan_count < UI_MAX_SCAN_RESULTS) {
      slot = _scan_count++;
    } else {   // full: evict the stalest
      slot = 0;
      for (int i = 1; i < _scan_count; i++) {
        if ((long)(_scan[i].seen - _scan[slot].seen) < 0) slot = i;
      }
    }
  }
  memcpy(_scan[slot].key, pub_key, key_len);
  _scan[slot].key_len = key_len;
  _scan[slot].node_type = node_type;
  _scan[slot].rx_snr4 = rx_snr4;
  _scan[slot].tx_snr4 = tx_snr4;
  _scan[slot].seen = millis();
  _last_activity = _scan[slot].seen;

  // keep newest-first, so the visible rows are the freshest and the overflow
  // row's time is the smallest of the ones hidden
  for (int i = slot; i > 0; i--) {
    if ((long)(_scan[i].seen - _scan[i-1].seen) > 0) {
      ScanResult t = _scan[i]; _scan[i] = _scan[i-1]; _scan[i-1] = t;
    } else break;
  }

  MESH_DEBUG_PRINTLN("nodeDiscovered: type=%d in=%ddB out=%ddB keylen=%d",
                     (uint32_t)node_type, (int32_t)(rx_snr4/4), (int32_t)(tx_snr4/4),
                     (uint32_t)key_len);
  if (_display != NULL && _display->isOn()) _next_refresh = 100;
}

void UITask::openChannelView(const MsgRowKey& key, const char* title) {
  ((ChannelViewScreen *) chan_view)->open(key, title);
  setCurrScreen(chan_view);
}

void UITask::closeChannelView() {
  const MsgRowKey& key = ((ChannelViewScreen *) chan_view)->key();
  _msgs.markRead(key);
  ((HomeScreen *) home)->gotoChannelsPage(key);
  setCurrScreen(home);
}

void UITask::openNodeInfo(const ScanResult& r) {
  char title[UI_MSG_SENDER_LEN];
  ContactInfo* c = the_mesh.lookupContactByPubKey(r.key, r.key_len);
  if (c != NULL && c->name[0] != 0) {
    strncpy(title, c->name, sizeof(title) - 1); title[sizeof(title) - 1] = 0;
  } else {
    snprintf(title, sizeof(title), "%02X%02X", r.key[0], r.key[1]);
  }
  _info_origin = 0;
  ((NodeInfoScreen *) node_info)->open(title, r.key, r.key_len, r.rx_snr4, r.tx_snr4,
                                       true, NODEINFO_SIGNAL);
  setCurrScreen(node_info);
}

// An advert only tells us the inbound SNR; there is no outbound figure, so the
// OUT meter is drawn empty. We only hold a 7-byte key prefix, unless the node is
// already a contact, in which case show its real key.
void UITask::openAdvertInfo(const AdvertPath& a) {
  const uint8_t* key = a.pubkey_prefix;
  uint8_t key_len = sizeof(a.pubkey_prefix);
  ContactInfo* c = the_mesh.lookupContactByPubKey(a.pubkey_prefix, sizeof(a.pubkey_prefix));
  if (c != NULL) { key = c->id.pub_key; key_len = PUB_KEY_SIZE; }

  _info_origin = 1;
  ((NodeInfoScreen *) node_info)->open(a.name, key, key_len, a.snr4, 0, false, NODEINFO_SIGNAL);
  setCurrScreen(node_info);
}

void UITask::openPathView(const AdvertPath& a) {
  ((PathScreen *) path_view)->open(a.path, a.path_len, a.name);
  setCurrScreen(path_view);
}

void UITask::openMatches(const uint8_t* hash, uint8_t hash_len) {
  ((MatchesScreen *) matches_view)->open(hash, hash_len);
  setCurrScreen(matches_view);
}

void UITask::closeNodeInfo() {
  if (_info_origin == 1) ((HomeScreen *) home)->gotoRecentPage();
  else                   ((HomeScreen *) home)->gotoScanPage();
  setCurrScreen(home);
}

void UITask::closePathView() {
  ((HomeScreen *) home)->gotoRecentPage();
  setCurrScreen(home);
}

void UITask::closeMatches() {
  setCurrScreen(path_view);
}

void UITask::gotoBluetoothScreen() {
  ((HomeScreen *) home)->gotoBluetoothPage();
  setCurrScreen(home);
}

void UITask::userLedHandler() {
#ifdef PIN_STATUS_LED
  int cur_time = millis();
  if (cur_time > next_led_change) {
    if (led_state == 0) {
      led_state = 1;
      if (_msgcount > 0) {
        last_led_increment = LED_ON_MSG_MILLIS;
      } else {
        last_led_increment = LED_ON_MILLIS;
      }
      next_led_change = cur_time + last_led_increment;
    } else {
      led_state = 0;
      next_led_change = cur_time + LED_CYCLE_MILLIS - last_led_increment;
    }
    digitalWrite(PIN_STATUS_LED, led_state == LED_STATE_ON);
  }
#endif
}

void UITask::setCurrScreen(UIScreen* c) {
  curr = c;
  _next_refresh = 100;
}

/*
  hardware-agnostic pre-shutdown activity should be done here
*/
void UITask::shutdown(bool restart){

  #ifdef PIN_BUZZER
  /* note: we have a choice here -
     we can do a blocking buzzer.loop() with non-deterministic consequences
     or we can set a flag and delay the shutdown for a couple of seconds
     while a non-blocking buzzer.loop() plays out in UITask::loop()
  */
  buzzer.shutdown();
  uint32_t buzzer_timer = millis(); // fail-safe shutdown
  while (buzzer.isPlaying() && (millis() - 2500) < buzzer_timer)
    buzzer.loop();

  #endif // PIN_BUZZER

  if (restart) {
    _board->reboot();
  } else {
    _display->turnOff();
    radio_driver.powerOff();
    _board->powerOff();
  }
}

bool UITask::isButtonPressed() const {
#ifdef PIN_USER_BTN
  return user_btn.isPressed();
#else
  return false;
#endif
}

// bars decay with age: 3 within 5 min, 2 within 10, 1 within 15, then hidden
int UITask::signalBars() const {
  if (_last_activity == 0) return 0;
  unsigned long age = millis() - _last_activity;
  if (age <=  5UL * 60 * 1000) return 3;
  if (age <= 10UL * 60 * 1000) return 2;
  if (age <= 15UL * 60 * 1000) return 1;
  return 0;
}

void UITask::loop() {
  char c = 0;

  // link activity: any received packet, or a reply to our scan (which bumps
  // _last_activity directly in nodeDiscovered)
  {
    uint32_t pkts = radio_driver.getPacketsRecv();
    if (pkts != _last_pkt_count) {
      _last_pkt_count = pkts;
      _last_activity = millis();
    }
  }

  // a scan's collection window just closed: pick the next autoscan interval.
  // Busy (found something AND heard traffic) -> 15 min, otherwise 3 min.
  if (_scan_deadline != 0 && millis() > _scan_deadline) {
    _scan_deadline = 0;
    uint32_t pkts = radio_driver.getPacketsRecv();
    bool busy = (_scan_hits > 0) && (pkts != _pkts_at_last_scan);
    _pkts_at_last_scan = pkts;
    _next_autoscan = millis() + (busy ? UI_SCAN_INTERVAL_BUSY_MILLIS : UI_SCAN_INTERVAL_IDLE_MILLIS);
    MESH_DEBUG_PRINTLN("scan done: hits=%d next in %d min", (uint32_t)_scan_hits,
                       (uint32_t)(busy ? 15 : 3));
    _next_refresh = 100;
  }
  if (_autoscan && _scan_deadline == 0 && millis() > _next_autoscan) {
    startScan();
  }

  // a peer just started pairing: wake the screen and show them the PIN
  bool pairing = isPairing();
  if (pairing && !_was_pairing && curr != splash) {
    if (_display != NULL && !_display->isOn()) _display->turnOn();
    _auto_off = millis() + AUTO_OFF_MILLIS;
    gotoBluetoothScreen();
  }
  _was_pairing = pairing;

#if UI_HAS_JOYSTICK
  int ev = user_btn.check();
  if (ev == BUTTON_EVENT_CLICK) {
    c = checkDisplayOn(KEY_ENTER);
  } else if (ev == BUTTON_EVENT_LONG_PRESS) {
    c = handleLongPress(KEY_ENTER);  // REVISIT: could be mapped to different key code
  }
  ev = joystick_left.check();
  if (ev == BUTTON_EVENT_CLICK) {
    c = checkDisplayOn(KEY_LEFT);
  } else if (ev == BUTTON_EVENT_LONG_PRESS) {
    c = handleLongPress(KEY_LEFT);
  }
  ev = joystick_right.check();
  if (ev == BUTTON_EVENT_CLICK) {
    c = checkDisplayOn(KEY_RIGHT);
  } else if (ev == BUTTON_EVENT_LONG_PRESS) {
    c = handleLongPress(KEY_RIGHT);
  }
  ev = back_btn.check();
  if (ev == BUTTON_EVENT_TRIPLE_CLICK) {
    c = handleTripleClick(KEY_SELECT);
  }
#elif defined(PIN_USER_BTN)
  int ev = user_btn.check();
  if (ev == BUTTON_EVENT_CLICK) {
    c = checkDisplayOn(KEY_NEXT);
  } else if (ev == BUTTON_EVENT_LONG_PRESS) {
    c = handleLongPress(KEY_ENTER);
  } else if (ev == BUTTON_EVENT_DOUBLE_CLICK) {
    c = handleDoubleClick(KEY_PREV);
  } else if (ev == BUTTON_EVENT_TRIPLE_CLICK) {
    c = handleTripleClick(KEY_SELECT);
  }
#endif
#if defined(PIN_USER_BTN_ANA)
  if (abs(millis() - _analogue_pin_read_millis) > 10) {
    int ev = analog_btn.check();
    if (ev == BUTTON_EVENT_CLICK) {
      c = checkDisplayOn(KEY_NEXT);
    } else if (ev == BUTTON_EVENT_LONG_PRESS) {
      c = handleLongPress(KEY_ENTER);
    } else if (ev == BUTTON_EVENT_DOUBLE_CLICK) {
      c = handleDoubleClick(KEY_PREV);
    } else if (ev == BUTTON_EVENT_TRIPLE_CLICK) {
      c = handleTripleClick(KEY_SELECT);
    }
    _analogue_pin_read_millis = millis();
  }
#endif
#if defined(BACKLIGHT_BTN)
  if (millis() > next_backlight_btn_check) {
    bool touch_state = digitalRead(PIN_BUTTON2);
#if defined(DISP_BACKLIGHT)
    digitalWrite(DISP_BACKLIGHT, !touch_state);
#elif defined(EXP_PIN_BACKLIGHT)
    expander.digitalWrite(EXP_PIN_BACKLIGHT, !touch_state);
#endif
    next_backlight_btn_check = millis() + 300;
  }
#endif

  if (c != 0 && curr) {
    curr->handleInput(c);
    _auto_off = millis() + AUTO_OFF_MILLIS;   // extend auto-off timer
    _next_refresh = 100;  // trigger refresh
  }

  userLedHandler();

#ifdef PIN_BUZZER
  if (buzzer.isPlaying())  buzzer.loop();
#endif

  if (curr) curr->poll();

  if (_display != NULL && _display->isOn()) {
    if (millis() >= _next_refresh && curr) {
      _display->startFrame();
      int delay_millis = curr->render(*_display);
      if (millis() < _alert_expiry) {  // render alert popup
        _display->setTextSize(1);
        int y = _display->height() / 3;
        int p = _display->height() / 32;
        _display->setColor(DisplayDriver::DARK);
        _display->fillRect(p, y, _display->width() - p*2, y);
        _display->setColor(DisplayDriver::LIGHT);  // draw box border
        _display->drawRect(p, y, _display->width() - p*2, y);
        _display->drawTextCentered(_display->width() / 2, y + p*3, _alert);
        _next_refresh = _alert_expiry;   // will need refresh when alert is dismissed
      } else {
        _next_refresh = millis() + delay_millis;
      }
      _display->endFrame();
    }
#if AUTO_OFF_MILLIS > 0
#ifdef KEEP_DISPLAY_ON_USB
    // Opt-in: refresh the auto-off deadline while externally powered, so the
    // timer counts from the moment external power is removed. Off by default
    // because OLED panels burn in quickly; only enable for LCD targets or
    // where the display is replaceable.
    if (board.isExternalPowered()) {
      _auto_off = millis() + AUTO_OFF_MILLIS;
    }
#endif
    if (millis() > _auto_off) {
      _display->turnOff();
    }
#endif
  }

#ifdef PIN_VIBRATION
  vibration.loop();
#endif

#ifdef AUTO_SHUTDOWN_MILLIVOLTS
  if (millis() > next_batt_chck) {
    uint16_t milliVolts = getBattMilliVolts();
    if (milliVolts > 0 && milliVolts < AUTO_SHUTDOWN_MILLIVOLTS) {
      if(!board.isExternalPowered()) {
        if (_display != NULL) {
          _display->startFrame();
          _display->setTextSize(2);
          _display->setColor(DisplayDriver::RED);
          _display->drawTextCentered(_display->width() / 2, 20, "Low Battery.");
          _display->drawTextCentered(_display->width() / 2, 40, "Shutting Down!");
          _display->endFrame();
          if (_display->isEink() == false) { delay(3000); }
        }
        shutdown();
      }
    }
    next_batt_chck = millis() + 8000;
  }
#endif
}

char UITask::checkDisplayOn(char c) {
  if (_display != NULL) {
    if (!_display->isOn()) {
      _display->turnOn();   // turn display on and consume event
      c = 0;
    }
    _auto_off = millis() + AUTO_OFF_MILLIS;   // extend auto-off timer
    _next_refresh = 0;  // trigger refresh
  }
  return c;
}

char UITask::handleLongPress(char c) {
  if (millis() - ui_started_at < 8000) {   // long press in first 8 seconds since startup -> CLI/rescue
    the_mesh.enterCLIRescue();
    c = 0;   // consume event
  }
  return c;
}

char UITask::handleDoubleClick(char c) {
  MESH_DEBUG_PRINTLN("UITask: double-click triggered");
  checkDisplayOn(c);
  return c;
}

char UITask::handleTripleClick(char c) {
  MESH_DEBUG_PRINTLN("UITask: triple click triggered");
  checkDisplayOn(c);
  toggleBuzzer();
  c = 0;
  return c;
}

bool UITask::getGPSState() {
  if (_sensors != NULL) {
    int num = _sensors->getNumSettings();
    for (int i = 0; i < num; i++) {
      if (strcmp(_sensors->getSettingName(i), "gps") == 0) {
        return !strcmp(_sensors->getSettingValue(i), "1");
      }
    }
  }
  return false;
}

void UITask::toggleGPS() {
    if (_sensors != NULL) {
    // toggle GPS on/off
    int num = _sensors->getNumSettings();
    for (int i = 0; i < num; i++) {
      if (strcmp(_sensors->getSettingName(i), "gps") == 0) {
        if (strcmp(_sensors->getSettingValue(i), "1") == 0) {
          _sensors->setSettingValue("gps", "0");
          _node_prefs->gps_enabled = 0;
          notify(UIEventType::ack);
        } else {
          _sensors->setSettingValue("gps", "1");
          _node_prefs->gps_enabled = 1;
          notify(UIEventType::ack);
        }
        the_mesh.savePrefs();
        // no alert: the GPS page already shows the state (struck-through icon
        // with "GPS Disabled", or the icon plus the acquisition bar)
        _next_refresh = 0;
        break;
      }
    }
  }
}

void UITask::toggleBuzzer() {
    // Toggle buzzer quiet mode
  #ifdef PIN_BUZZER
    if (buzzer.isQuiet()) {
      buzzer.quiet(false);
      notify(UIEventType::ack);
    } else {
      buzzer.quiet(true);
    }
    _node_prefs->buzzer_quiet = buzzer.isQuiet();
    the_mesh.savePrefs();
    showAlert(buzzer.isQuiet() ? "Buzzer: OFF" : "Buzzer: ON", 800);
    _next_refresh = 0;  // trigger refresh
  #endif
}
