#pragma once

#include <Arduino.h>
#include <string.h>
#include <stdlib.h>

// Satellite sky view, fed from the NMEA sentences MicroNMEA itself ignores.
//
// MicroNMEA::process() only handles GGA and RMC; everything else is passed to
// the "unknown sentence" handler. GSV (satellites in view: elevation, azimuth,
// SNR) and GSA (fix type, which PRNs are used in the solution) arrive there and
// were previously discarded. They are the only source of acquisition progress:
// GGA's satellite count is "satellites used in the fix", which stays at 0 until
// the fix lands and so says nothing about how close you are.
//
// Entries expire on a timer rather than being tracked per GSV cycle, because a
// multi-constellation receiver interleaves GPGSV / GLGSV / GAGSV / GBGSV cycles
// and reconciling their message counters is far more code than it is worth.

#ifndef GNSS_MAX_SATS
  #define GNSS_MAX_SATS  24
#endif

// a satellite not re-reported within this long is dropped
#ifndef GNSS_SAT_TTL_MILLIS
  #define GNSS_SAT_TTL_MILLIS  12000
#endif

// SNR at/above which a satellite is usable for a fix (dB-Hz)
#ifndef GNSS_GOOD_SNR
  #define GNSS_GOOD_SNR  30
#endif

// satellites above GNSS_GOOD_SNR needed for a 3D fix
#ifndef GNSS_SATS_FOR_FIX
  #define GNSS_SATS_FOR_FIX  4
#endif

struct SatInfo {
  uint8_t  prn;
  char     talker;      // 'P' GPS, 'L' GLONASS, 'A' Galileo, 'B' BeiDou, 'N' GNSS
  uint8_t  elev;        // degrees, 0..90
  uint16_t azim;        // degrees, 0..359 (0 = north)
  uint8_t  snr;         // dB-Hz, 0 = being searched for but not tracked
  unsigned long seen;
  unsigned long used_seen;   // last time a GSA listed this PRN
};

class GNSSSkyView {
  SatInfo _sats[GNSS_MAX_SATS];
  int _count;
  uint8_t _fix_type;            // 0 unknown, 1 none, 2 = 2D, 3 = 3D
  unsigned long _fix_seen;

  SatInfo* find(uint8_t prn, char talker) {
    for (int i = 0; i < _count; i++) {
      if (_sats[i].prn == prn && _sats[i].talker == talker) return &_sats[i];
    }
    return NULL;
  }

  // evict the stalest entry when full, so a busy sky can't lock us out
  SatInfo* alloc(unsigned long now) {
    if (_count < GNSS_MAX_SATS) return &_sats[_count++];
    int oldest = 0;
    for (int i = 1; i < _count; i++) {
      if ((long)(_sats[i].seen - _sats[oldest].seen) < 0) oldest = i;
    }
    return &_sats[oldest];
  }

  void markUsed(uint8_t prn, unsigned long now) {
    // GSA may be sent with talker 'N' while GSV uses per-constellation talkers,
    // so match on PRN alone. Cross-constellation PRN collisions just mean a dot
    // is drawn filled slightly too eagerly.
    for (int i = 0; i < _count; i++) {
      if (_sats[i].prn == prn) _sats[i].used_seen = now;
    }
  }

  void processGSV(char* f[], int nf, char talker, unsigned long now) {
    if (nf < 4) return;
    // f[0]=numMsgs f[1]=msgNum f[2]=satsInView, then groups of 4.
    // NMEA 4.11 appends a signalId field; integer division drops it.
    int groups = (nf - 3) / 4;
    for (int g = 0; g < groups; g++) {
      const int b = 3 + g * 4;
      if (f[b][0] == 0) continue;                 // no PRN, padding group
      int prn = atoi(f[b]);
      if (prn <= 0 || prn > 255) continue;

      SatInfo* s = find((uint8_t) prn, talker);
      if (s == NULL) {
        s = alloc(now);
        s->used_seen = 0;
      }
      s->prn    = (uint8_t) prn;
      s->talker = talker;
      s->elev   = f[b+1][0] ? (uint8_t) atoi(f[b+1]) : 0;
      s->azim   = f[b+2][0] ? (uint16_t) atoi(f[b+2]) : 0;
      s->snr    = f[b+3][0] ? (uint8_t) atoi(f[b+3]) : 0;   // blank = not tracked
      s->seen   = now;
    }
  }

  void processGSA(char* f[], int nf, unsigned long now) {
    if (nf < 2) return;
    uint8_t ft = (uint8_t) atoi(f[1]);            // 1 none, 2 = 2D, 3 = 3D
    if (ft > 3) ft = 0;

    // several GSA sentences (one per constellation) arrive back to back; take
    // the best of them rather than letting the last one win
    if (_fix_seen == 0 || (now - _fix_seen) > 2000) _fix_type = ft;
    else if (ft > _fix_type) _fix_type = ft;
    _fix_seen = now;

    for (int i = 2; i < nf && i < 14; i++) {      // PRNs used, 12 slots
      if (f[i][0] == 0) continue;
      int prn = atoi(f[i]);
      if (prn > 0 && prn <= 255) markUsed((uint8_t) prn, now);
    }
  }

public:
  GNSSSkyView() : _count(0), _fix_type(0), _fix_seen(0) { }

  void reset() { _count = 0; _fix_type = 0; _fix_seen = 0; }

  // 'sentence' is a checksum-validated NMEA line starting with '$'
  void process(const char* sentence, unsigned long now) {
    if (sentence == NULL || sentence[0] != '$') return;
    if (strlen(sentence) < 7 || sentence[6] != ',') return;   // not $ttMMM,
    const char talker = sentence[2];
    const char* msgid = &sentence[3];

    const bool is_gsv = (msgid[0] == 'G' && msgid[1] == 'S' && msgid[2] == 'V');
    const bool is_gsa = (msgid[0] == 'G' && msgid[1] == 'S' && msgid[2] == 'A');
    if (!is_gsv && !is_gsa) return;

    // split the comma-separated body, stopping at the '*' checksum
    char buf[24][8];
    char* f[24];
    int nf = 0;
    const char* p = &sentence[7];
    while (nf < 24) {
      const char* e = p;
      while (*e && *e != ',' && *e != '*') e++;
      int len = e - p;
      if (len > 7) len = 7;
      memcpy(buf[nf], p, len);
      buf[nf][len] = 0;
      f[nf] = buf[nf];
      nf++;
      if (*e != ',') break;
      p = e + 1;
    }

    if (is_gsv) processGSV(f, nf, talker, now);
    else        processGSA(f, nf, now);
  }

  // drop satellites (and the fix type) we have not heard about recently
  void expire(unsigned long now) {
    int w = 0;
    for (int i = 0; i < _count; i++) {
      if ((now - _sats[i].seen) <= GNSS_SAT_TTL_MILLIS) {
        if (w != i) _sats[w] = _sats[i];
        w++;
      }
    }
    _count = w;
    if (_fix_seen != 0 && (now - _fix_seen) > GNSS_SAT_TTL_MILLIS) {
      _fix_type = 0;
      _fix_seen = 0;
    }
  }

  int count() const { return _count; }
  const SatInfo& sat(int i) const { return _sats[i]; }
  uint8_t fixType() const { return _fix_type; }

  bool isUsed(const SatInfo& s, unsigned long now) const {
    return s.used_seen != 0 && (now - s.used_seen) <= GNSS_SAT_TTL_MILLIS;
  }

  // satellites tracked at or above 'min_snr' dB-Hz
  int countAbove(int min_snr) const {
    int n = 0;
    for (int i = 0; i < _count; i++) {
      if (_sats[i].snr >= min_snr) n++;
    }
    return n;
  }

  // 0..100, how close the receiver looks to producing a fix
  int acquisitionPercent() const {
    if (_fix_type >= 3) return 100;
    int strong = countAbove(GNSS_GOOD_SNR);
    int pc = (strong * 100) / GNSS_SATS_FOR_FIX;
    if (pc > 100) pc = 100;
    if (_fix_type == 2 && pc < 75) pc = 75;   // 2D fix: nearly there
    if (pc == 0 && _count > 0) pc = 4;        // seeing satellites, none strong yet
    return pc;
  }
};
