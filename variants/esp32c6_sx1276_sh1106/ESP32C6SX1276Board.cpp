#include <Arduino.h>
#include "target.h"

ESP32C6SX1276Board board;

#if defined(P_LORA_SCLK)
  static SPIClass spi(0);
  // RadioLib defaults to 2 MHz. -D LORA_SPI_HZ=1000000 slows it, which helps if
  // the SPI wiring is marginal (long jumpers, poor ground).
  #ifdef LORA_SPI_HZ
    RADIO_CLASS radio = new Module(P_LORA_NSS, P_LORA_DIO_0, P_LORA_RESET, P_LORA_DIO_1,
                                   spi, SPISettings(LORA_SPI_HZ, MSBFIRST, SPI_MODE0));
  #else
    RADIO_CLASS radio = new Module(P_LORA_NSS, P_LORA_DIO_0, P_LORA_RESET, P_LORA_DIO_1, spi);
  #endif
#else
  RADIO_CLASS radio = new Module(P_LORA_NSS, P_LORA_DIO_0, P_LORA_RESET, P_LORA_DIO_1);
#endif

WRAPPER_CLASS radio_driver(radio, board);

ESP32RTCClock fallback_clock;
AutoDiscoverRTCClock rtc_clock(fallback_clock);
SensorManager sensors;

#ifdef DISPLAY_CLASS
  DISPLAY_CLASS display;
  MomentaryButton user_btn(PIN_USER_BTN, 1000, false, true);
#endif

bool radio_init() {
  fallback_clock.begin();
  rtc_clock.begin(Wire);

#if defined(P_LORA_SCLK)
  return radio.std_init(&spi);
#else
  return radio.std_init();
#endif
}

mesh::LocalIdentity radio_new_identity() {
  RadioNoiseListener rng(radio);
  return mesh::LocalIdentity(&rng);  // create new random identity
}
