#pragma once

#include <Arduino.h>
#include <helpers/ESP32Board.h>

class ESP32C6SX1276Board : public ESP32Board {
public:
  void begin() {
    ESP32Board::begin();
  }

  uint32_t getIRQGpio() override {
    return P_LORA_DIO_0; // SX1276 IRQ is on DIO0
  }

  const char* getManufacturerName() const override {
    return "ESP32-C6 SX1276 SH1106";
  }
};
