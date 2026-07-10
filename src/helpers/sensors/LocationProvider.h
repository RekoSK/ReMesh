#pragma once

#include "Mesh.h"
#include "GNSSSkyView.h"


class LocationProvider {
protected:
    bool _time_sync_needed = true;

public:
    // Satellite sky view (GSV/GSA). NULL when the provider cannot supply one,
    // so providers other than MicroNMEA need no change.
    virtual GNSSSkyView* getSkyView() { return NULL; }

    virtual void syncTime() { _time_sync_needed = true; }
    virtual bool waitingTimeSync() { return _time_sync_needed; }
    virtual long getLatitude() = 0;
    virtual long getLongitude() = 0;
    virtual long getAltitude() = 0;
    virtual long satellitesCount() = 0;
    virtual bool isValid() = 0;
    virtual long getTimestamp() = 0;
    virtual void sendSentence(const char * sentence);
    virtual void reset() = 0;
    virtual void begin() = 0;
    virtual void stop() = 0;
    virtual void loop() = 0;
    virtual bool isEnabled() = 0;
};
