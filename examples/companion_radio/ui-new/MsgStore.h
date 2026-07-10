#pragma once

#include <Arduino.h>
#include <string.h>

// On-device copy of recently received messages, so they can be read without the
// phone. The buffer is statically allocated, so emptying it on connect would
// free nothing -- messages are kept regardless of whether the app is connected,
// and the ring simply overwrites the oldest.
//
// Independent of the offline queue, which the app drains on connect. Never
// persisted to flash; a reboot loses everything.
//
// A "row" in the Channels list is either a group channel (channel_idx >= 0) or
// one remote contact that has sent us direct messages (channel_idx == DM_CHANNEL,
// keyed by sender name).

#ifndef UI_MSG_STORE_SIZE
  #define UI_MSG_STORE_SIZE  48
#endif

#define UI_MSG_SENDER_LEN  24
#define UI_MSG_TEXT_LEN    80

#define DM_CHANNEL  (-1)

// Word-wrap 'text' into dest[], breaking at spaces where possible and hard-
// breaking words longer than a line. Always produces at least one line.
// Returns the number of lines written (capped at max_lines).
static inline int msgWrapLines(const char* text, char dest[][UI_MSG_TEXT_LEN], int max_lines, int cols) {
  if (cols < 1) cols = 1;
  if (cols > UI_MSG_TEXT_LEN - 1) cols = UI_MSG_TEXT_LEN - 1;
  int n = 0, i = 0;
  int len = strlen(text);
  if (len == 0) { dest[0][0] = 0; return 1; }
  while (i < len && n < max_lines) {
    int take = len - i;
    if (take > cols) {
      take = cols;
      // back up to the last space so words stay whole; if the word is longer
      // than a line, fall back to a hard break at 'cols'
      int b = take;
      while (b > 0 && text[i + b] != ' ') b--;
      if (b > 0) take = b;
    }
    // don't render trailing spaces (a run of spaces at the break would
    // otherwise pad the line out)
    const int start = i;
    int emit = take;
    while (emit > 0 && text[start + emit - 1] == ' ') emit--;

    i += take;                               // always advances: take >= 1
    while (i < len && text[i] == ' ') i++;   // eat the break space(s)

    if (emit == 0) continue;                 // whitespace-only line, skip it
    memcpy(dest[n], &text[start], emit);
    dest[n][emit] = 0;
    n++;
  }
  return n == 0 ? 1 : n;
}

struct StoredMsg {
  uint32_t timestamp;
  int8_t   channel_idx;             // >=0 group channel, DM_CHANNEL for a direct msg
  bool     unread;
  char     sender[UI_MSG_SENDER_LEN];
  char     text[UI_MSG_TEXT_LEN];
};

// identifies one row of the Channels list
struct MsgRowKey {
  int8_t channel_idx;
  char   sender[UI_MSG_SENDER_LEN];   // only meaningful when channel_idx == DM_CHANNEL

  bool matches(const StoredMsg& m) const {
    if (channel_idx != m.channel_idx) return false;
    if (channel_idx != DM_CHANNEL) return true;
    return strncmp(sender, m.sender, UI_MSG_SENDER_LEN) == 0;
  }
  bool equals(const MsgRowKey& o) const {
    if (channel_idx != o.channel_idx) return false;
    if (channel_idx != DM_CHANNEL) return true;
    return strncmp(sender, o.sender, UI_MSG_SENDER_LEN) == 0;
  }
};

class MsgStore {
  StoredMsg _msgs[UI_MSG_STORE_SIZE];
  int _count;      // number of valid entries (<= UI_MSG_STORE_SIZE)
  int _head;       // index one past the newest entry

  StoredMsg* push() {
    StoredMsg* m = &_msgs[_head];
    _head = (_head + 1) % UI_MSG_STORE_SIZE;
    if (_count < UI_MSG_STORE_SIZE) _count++;
    return m;
  }

  StoredMsg* _at(int i) {
    if (i < 0 || i >= _count) return NULL;
    int oldest = (_head - _count + UI_MSG_STORE_SIZE * 2) % UI_MSG_STORE_SIZE;
    return &_msgs[(oldest + i) % UI_MSG_STORE_SIZE];
  }

public:
  MsgStore() : _count(0), _head(0) { }

  // Drop everything. Called when the phone app connects: from then on the app
  // owns the messages, and nothing is retained on the device.
  void clear() { _count = 0; _head = 0; }

  int count() const { return _count; }

  // 0 = oldest retained message
  const StoredMsg* at(int i) const {
    return const_cast<MsgStore*>(this)->_at(i);
  }

  void add(int8_t channel_idx, const char* sender, const char* text, uint32_t timestamp) {
    StoredMsg* m = push();
    m->timestamp = timestamp;
    m->channel_idx = channel_idx;
    m->unread = true;
    strncpy(m->sender, sender ? sender : "", UI_MSG_SENDER_LEN - 1);
    m->sender[UI_MSG_SENDER_LEN - 1] = 0;
    strncpy(m->text, text ? text : "", UI_MSG_TEXT_LEN - 1);
    m->text[UI_MSG_TEXT_LEN - 1] = 0;
  }

  // Channel text arrives as "<sender>: <message>" (see BaseChatMesh::sendGroupMessage).
  // Split it so the sender can be shown above the body. Falls back to the whole
  // string as the body when the prefix is absent.
  void addChannelMsg(int8_t channel_idx, const char* raw, uint32_t timestamp) {
    const char* sep = strstr(raw, ": ");
    if (sep == NULL || sep == raw || (sep - raw) >= UI_MSG_SENDER_LEN) {
      add(channel_idx, "", raw, timestamp);
      return;
    }
    char sender[UI_MSG_SENDER_LEN];
    int n = sep - raw;
    memcpy(sender, raw, n);
    sender[n] = 0;
    add(channel_idx, sender, sep + 2, timestamp);
  }

  void addDirectMsg(const char* from_name, const char* text, uint32_t timestamp) {
    add(DM_CHANNEL, from_name, text, timestamp);
  }

  int unreadFor(const MsgRowKey& key) const {
    int n = 0;
    for (int i = 0; i < _count; i++) {
      const StoredMsg* m = at(i);
      if (m->unread && key.matches(*m)) n++;
    }
    return n;
  }

  int countFor(const MsgRowKey& key) const {
    int n = 0;
    for (int i = 0; i < _count; i++) {
      if (key.matches(*at(i))) n++;
    }
    return n;
  }

  void markRead(const MsgRowKey& key) {
    for (int i = 0; i < _count; i++) {
      StoredMsg* m = _at(i);
      if (key.matches(*m)) m->unread = false;
    }
  }

  void clearAllUnread() {
    for (int i = 0; i < _count; i++) _at(i)->unread = false;
  }

  // Distinct DM senders, oldest-first by their most recent message. Returns how
  // many were written to dest.
  int listDirectSenders(char dest[][UI_MSG_SENDER_LEN], int max_num) const {
    int n = 0;
    for (int i = 0; i < _count && n < max_num; i++) {
      const StoredMsg* m = at(i);
      if (m->channel_idx != DM_CHANNEL || m->sender[0] == 0) continue;
      bool seen = false;
      for (int j = 0; j < n; j++) {
        if (strncmp(dest[j], m->sender, UI_MSG_SENDER_LEN) == 0) { seen = true; break; }
      }
      if (!seen) {
        strncpy(dest[n], m->sender, UI_MSG_SENDER_LEN - 1);
        dest[n][UI_MSG_SENDER_LEN - 1] = 0;
        n++;
      }
    }
    return n;
  }
};
