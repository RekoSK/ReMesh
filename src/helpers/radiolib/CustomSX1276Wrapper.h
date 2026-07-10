#pragma once

#include "CustomSX1276.h"
#include "RadioLibWrappers.h"

#ifndef USE_SX1276
#define USE_SX1276
#endif

class CustomSX1276Wrapper : public RadioLibWrapper {
public:
  CustomSX1276Wrapper(CustomSX1276& radio, mesh::MainBoard& board) : RadioLibWrapper(radio, board) { }

  void setParams(float freq, float bw, uint8_t sf, uint8_t cr) override {
    ((CustomSX1276 *)_radio)->setFrequency(freq);
    ((CustomSX1276 *)_radio)->setSpreadingFactor(sf);
    ((CustomSX1276 *)_radio)->setBandwidth(bw);
    ((CustomSX1276 *)_radio)->setCodingRate(cr);
    updatePreamble(sf);
  }

  // Diagnose a failed radio call by reading the chip directly.
  //   RegVersion 0x12 + RegOpMode bit7 clear -> SPI fine, chip reverted to its
  //     FSK/OOK power-on default, i.e. it RESET (brown-out or NRESET asserted)
  //   RegVersion 0x00 or 0xFF               -> SPI/MISO dead at this instant
  void logRadioFault(const char* where, int err) override {
    uint8_t ver = ((CustomSX1276 *)_radio)->readRadioReg(0x42);  // RegVersion
    uint8_t op  = ((CustomSX1276 *)_radio)->readRadioReg(0x01);  // RegOpMode
    const char* verdict;
    if (ver == 0x00 || ver == 0xFF) {
      verdict = "SPI-DEAD";
    } else if (ver == 0x12 && (op & 0x80) == 0) {
      verdict = "CHIP-RESET(FSK)";
    } else if (ver == 0x12) {
      verdict = "chip-ok";
    } else {
      verdict = "BAD-VERSION";
    }
    MESH_DEBUG_PRINTLN("  RADIO FAULT %s(%d): RegVersion=0x%02X RegOpMode=0x%02X -> %s",
                       where, err, (uint32_t)ver, (uint32_t)op, verdict);
  }

  bool isReceivingPacket() override {
    return ((CustomSX1276 *)_radio)->isReceiving();
  }
  float getCurrentRSSI() override {
    return ((CustomSX1276 *)_radio)->getRSSI(false);
  }
  float getLastRSSI() const override { return ((CustomSX1276 *)_radio)->getRSSI(); }
  float getLastSNR() const override { return ((CustomSX1276 *)_radio)->getSNR(); }

  float packetScore(float snr, int packet_len) override {
    int sf = ((CustomSX1276 *)_radio)->spreadingFactor;
    return packetScoreInt(snr, sf, packet_len);
  }
  uint8_t getSpreadingFactor() const override { return ((CustomSX1276 *)_radio)->spreadingFactor; }
};
