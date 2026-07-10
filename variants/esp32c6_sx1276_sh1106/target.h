#pragma once

#define RADIOLIB_STATIC_ONLY 1
#include <RadioLib.h>
#include <ESP32C6SX1276Board.h>
#include <helpers/radiolib/RadioLibWrappers.h>
#include <helpers/ESP32Board.h>
#include <helpers/radiolib/CustomSX1276Wrapper.h>
#include <helpers/AutoDiscoverRTCClock.h>
#include <helpers/SensorManager.h>
#ifdef DISPLAY_CLASS
  #include <helpers/ui/SH1106Display.h>
  #include <helpers/ui/MomentaryButton.h>
#endif

extern ESP32C6SX1276Board board;
extern WRAPPER_CLASS radio_driver;
extern AutoDiscoverRTCClock rtc_clock;
extern SensorManager sensors;

#ifdef DISPLAY_CLASS
  extern DISPLAY_CLASS display;
  extern MomentaryButton user_btn;
#endif

bool radio_init();
mesh::LocalIdentity radio_new_identity();
