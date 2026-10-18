/*
 * NEXUSHOME - responsive command handling revision
 * Target: original ESP32 with Bluetooth Classic support.
 * Libraries: DHT sensor library (+ Adafruit Unified Sensor), U8g2.
 *
 * No command cooldown: valid ON/OFF commands are not time-filtered.
 * Bounded input batches; unchanged Boolean states are not re-saved.
 * Bluetooth status updates are coalesced; app 'S' requests are prioritized.
 * Actual Boolean changes still attempt synchronous NVS persistence.
 * Existing mode/override behavior and curtain NVS keys are preserved.
 * SENSOR ERROR distinguishes failed reads from startup initialization.
 *
 * Timers are cooperative, not hard real-time. NVS, DHT, I2C and Bluetooth
 * operations can block. Hardware verification is required before deployment.
 * Curtain restoration uses saved time, not measured physical position.
 */

#include <DHT.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <HardwareSerial.h>
#include <BluetoothSerial.h>
#include <Preferences.h>
#include <math.h>

#define DHTPIN 33
#define DHTTYPE DHT22
#define RELAY_LIGHT 2
#define RELAY_FAN 15
#define RELAY_CURTAIN_OPEN 18
#define RELAY_CURTAIN_CLOSE 19
#define BUZZER_PIN 13
#define VC_RX 16
#define VC_TX 17

constexpr float TEMP_THRESHOLD_HIGH = 30.0f;
constexpr float TEMP_THRESHOLD_LOW = 29.0f; // 1 C hysteresis, unchanged
constexpr uint32_t SENSOR_INTERVAL = 2500;
constexpr uint32_t CURTAIN_MOVE_TIME = 25000;
constexpr uint32_t CURTAIN_SAVE_INTERVAL = 500;
constexpr uint32_t DISPLAY_INTERVAL = 50;
constexpr uint32_t HEARTBEAT_INTERVAL = 500;
constexpr uint32_t SPLASH_TIME = 1900;
constexpr uint32_t TEMP_INIT_MIN_TIME = 2500;
constexpr uint32_t STATUS_CHANGE_INTERVAL = 100;
constexpr uint32_t STATUS_HEARTBEAT_INTERVAL = 2000;
constexpr uint8_t MAX_VOICE_BYTES_PER_PASS = 8;
constexpr uint8_t MAX_BT_BYTES_PER_PASS = 8;
constexpr uint8_t RELAY_ON = LOW;
constexpr uint8_t RELAY_OFF = HIGH;

#define CMD_LIGHT_ON 0xA0
#define CMD_LIGHT_OFF 0xA1
#define CMD_FAN_ON 0xB0
#define CMD_FAN_OFF 0xB1
#define CMD_MODE_AUTO 0xC0
#define CMD_MODE_MAN 0xC1
#define CMD_CURTAIN_OPEN 0xD0
#define CMD_CURTAIN_CLOSE 0xD1

U8G2_SSD1309_128X64_NONAME0_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
DHT dht(DHTPIN, DHTTYPE);
HardwareSerial VC02(1);
BluetoothSerial SerialBT;
Preferences preferences;

enum CurtainState : uint8_t { C_CLOSED, C_OPENING, C_OPEN, C_CLOSING };
CurtainState curtainStatus = C_CLOSED;
float temp = 0.0f;
float lastValidTemp = 0.0f;
bool sensorOK = false;
uint8_t dhtFailCount = 0;
bool isAutoMode = false;
bool fanState = false;
bool lightState = false;
bool autoFanOverrideOFF = false;
bool autoFanOverrideON = false;

// Cached values represent the last successfully saved Boolean values.
bool nvsReady = false;
bool savedLight = false;
bool savedFan = false;
bool savedAuto = false;
uint32_t curtainElapsed = 0;
uint32_t curtainProgress = 0;
uint32_t curtainMoveStartedAt = 0;
uint32_t lastCurtainSave = 0;
uint32_t bootStartedAt = 0;
uint32_t lastSensorRead = 0;
uint32_t lastDisplayFrame = 0;
uint32_t lastHeartbeatFrame = 0;
uint32_t lastStatusSent = 0;
bool splashActive = true;
bool displayDirty = true;
bool statusPending = true;
bool statusRequested = false;
bool buzzerOn = false;
uint32_t buzzerOffAt = 0;
bool voiceWaitingForCommand = false;

void processCommand(uint8_t cmd, const char *source);
void serviceCurtain();
void serviceDisplay();
void drawSplash(uint32_t now);
void drawDashboard(uint32_t now);
void sendFullStatus();

static bool timeReached(uint32_t now, uint32_t deadline) {
  return (int32_t)(now - deadline) >= 0;
}

void markChanged() {
  displayDirty = true;
  statusPending = true;
}

bool tempInitializing(uint32_t now) {
  return !sensorOK || (uint32_t)(now - bootStartedAt) < TEMP_INIT_MIN_TIME;
}

void saveBoolIfChanged(const char *key, bool value, bool &savedValue) {
  if (!nvsReady || value == savedValue) return;
  if (preferences.putBool(key, value) == sizeof(bool)) {
    savedValue = value;
  } else {
    Serial.printf("NVS SAVE FAILED: %s\n", key);
  }
}

void saveCurtainSnapshot(uint32_t elapsed) {
  if (!nvsReady) return;
  // Legacy keys retained for compatibility with the original sketch.
  // These two writes are NOT one atomic transaction.
  const size_t stateBytes = preferences.putInt("curtain_state", (int)curtainStatus);
  const size_t elapsedBytes = preferences.putUInt("c_elapsed", elapsed);
  if (stateBytes != sizeof(int32_t) || elapsedBytes != sizeof(uint32_t)) {
    Serial.println("NVS CURTAIN SNAPSHOT FAILED");
  }
}

void startBeep(uint32_t durationMs) {
  digitalWrite(BUZZER_PIN, HIGH);
  buzzerOn = true;
  buzzerOffAt = millis() + durationMs;
}

void serviceBuzzer() {
  if (buzzerOn && timeReached(millis(), buzzerOffAt)) {
    digitalWrite(BUZZER_PIN, LOW);
    buzzerOn = false;
  }
}

void stopCurtainRelays() {
  digitalWrite(RELAY_CURTAIN_OPEN, RELAY_OFF);
  digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_OFF);
}

void setLight(bool on) {
  if (lightState != on) {
    digitalWrite(RELAY_LIGHT, on ? RELAY_ON : RELAY_OFF);
    lightState = on;
    markChanged();
  }
  saveBoolIfChanged("light_state", lightState, savedLight);
}

void setFan(bool on) {
  if (fanState != on) {
    digitalWrite(RELAY_FAN, on ? RELAY_ON : RELAY_OFF);
    fanState = on;
    markChanged();
  }
  saveBoolIfChanged("fan_state", fanState, savedFan);
}

void setMode(bool automatic) {
  if (isAutoMode != automatic) {
    isAutoMode = automatic;
    markChanged();
  }
  saveBoolIfChanged("auto_mode", isAutoMode, savedAuto);
}

void startCurtain(CurtainState movingState) {
  stopCurtainRelays();
  curtainElapsed = 0;
  curtainProgress = 0;
  curtainStatus = movingState;
  // Preserve original save-before-start behavior for a new motor movement.
  saveCurtainSnapshot(0);
  const uint32_t startNow = millis();
  curtainMoveStartedAt = startNow;
  lastCurtainSave = startNow;
  digitalWrite(movingState == C_OPENING ? RELAY_CURTAIN_OPEN : RELAY_CURTAIN_CLOSE,
               RELAY_ON);
  markChanged();
}

void serviceCurtain() {
  if (curtainStatus != C_OPENING && curtainStatus != C_CLOSING) return;
  const uint32_t now = millis();
  const uint32_t thisRun = (uint32_t)(now - curtainMoveStartedAt);
  // Compare with remaining time before adding, avoiding addition overflow.
  const uint32_t remaining = CURTAIN_MOVE_TIME - curtainElapsed;
  const uint32_t liveElapsed = thisRun >= remaining
      ? CURTAIN_MOVE_TIME : curtainElapsed + thisRun;
  if (curtainProgress != liveElapsed) {
    curtainProgress = liveElapsed;
    displayDirty = true;
  }
  if (liveElapsed >= CURTAIN_MOVE_TIME) {
    stopCurtainRelays(); // Stop before doing persistence or communication.
    curtainStatus = curtainStatus == C_OPENING ? C_OPEN : C_CLOSED;
    curtainElapsed = 0;
    curtainProgress = 0;
    saveCurtainSnapshot(0);
    markChanged();
    return;
  }
  if ((uint32_t)(now - lastCurtainSave) >= CURTAIN_SAVE_INTERVAL) {
    if (nvsReady && preferences.putUInt("c_elapsed", liveElapsed) != sizeof(uint32_t)) {
      Serial.println("NVS CURTAIN CHECKPOINT FAILED");
    }
    lastCurtainSave = now;
  }
}

void processCommand(uint8_t cmd, const char *source) {
  (void)source;
  switch (cmd) {
    case CMD_LIGHT_ON: setLight(true); break;
    case CMD_LIGHT_OFF: setLight(false); break;
    case CMD_FAN_ON:
      autoFanOverrideON = true;
      autoFanOverrideOFF = false;
      setFan(true);
      break;
    case CMD_FAN_OFF:
      autoFanOverrideON = false;
      autoFanOverrideOFF = isAutoMode && sensorOK && temp >= TEMP_THRESHOLD_HIGH;
      setFan(false);
      break;
    case CMD_MODE_AUTO:
      autoFanOverrideOFF = false;
      autoFanOverrideON = false;
      setMode(true);
      break;
    case CMD_MODE_MAN:
      autoFanOverrideOFF = false;
      autoFanOverrideON = false;
      // Original behavior: entering MANUAL turns the fan OFF.
      // Apply the output before either possible flash write.
      if (fanState) {
        digitalWrite(RELAY_FAN, RELAY_OFF);
        fanState = false;
        markChanged();
      }
      setMode(false);
      saveBoolIfChanged("fan_state", fanState, savedFan);
      break;
    case CMD_CURTAIN_OPEN:
      if (curtainStatus == C_CLOSED) startCurtain(C_OPENING);
      break;
    case CMD_CURTAIN_CLOSE:
      if (curtainStatus == C_OPEN) startCurtain(C_CLOSING);
      break;
    default: break;
  }
}

void serviceVoice() {
  uint8_t count = 0;
  while (VC02.available() > 0 && count < MAX_VOICE_BYTES_PER_PASS) {
    ++count;
    const uint8_t incoming = (uint8_t)VC02.read();
    if (!voiceWaitingForCommand) {
      if (incoming == 0x20) voiceWaitingForCommand = true;
      continue;
    }
    if (incoming == 0x20) continue;
    voiceWaitingForCommand = false;
    // No cooldown. Unknown command bytes have no effect.
    processCommand(incoming, "Voice");
    serviceCurtain();
    serviceBuzzer();
  }
}

void serviceBluetoothInput() {
  uint8_t count = 0;
  while (SerialBT.available() > 0 && count < MAX_BT_BYTES_PER_PASS) {
    ++count;
    const char cmd = (char)SerialBT.read();
    switch (cmd) {
      case 'S': statusRequested = true; break;
      case 'A': processCommand(CMD_LIGHT_ON, "App"); break;
      case 'a': processCommand(CMD_LIGHT_OFF, "App"); break;
      case 'B': processCommand(CMD_FAN_ON, "App"); break;
      case 'b': processCommand(CMD_FAN_OFF, "App"); break;
      case 'C': processCommand(CMD_MODE_AUTO, "App"); break;
      case 'c': processCommand(CMD_MODE_MAN, "App"); break;
      case 'D': processCommand(CMD_CURTAIN_OPEN, "App"); break;
      case 'd': processCommand(CMD_CURTAIN_CLOSE, "App"); break;
      default: break;
    }
    serviceCurtain();
    serviceBuzzer();
  }
}

void serviceSensor() {
  const uint32_t now = millis();
  if ((uint32_t)(now - lastSensorRead) < SENSOR_INTERVAL) return;
  lastSensorRead = now;
  const float newTemp = dht.readTemperature();
  // Reject failed reads and temperatures outside the DHT22 rated range.
  if (!isnan(newTemp) && !isinf(newTemp) && newTemp >= -40.0f && newTemp <= 80.0f) {
    const bool recovering = dhtFailCount > 0;
    temp = newTemp;
    lastValidTemp = newTemp;
    sensorOK = true;
    dhtFailCount = 0;
    if (recovering) Serial.println("DHT: valid reading restored");
  } else {
    temp = lastValidTemp;
    if (dhtFailCount < 255) ++dhtFailCount;
    if (dhtFailCount >= 3) sensorOK = false;
    Serial.printf("DHT: failed read at %lu ms, consecutive=%u\n",
                  (unsigned long)now, (unsigned)dhtFailCount);
  }
  markChanged();
}

void serviceAutoFan() {
  const uint32_t now = millis(); // Fresh time, not a pre-command timestamp.
  if (splashActive || !isAutoMode || tempInitializing(now)) return;
  if (temp >= TEMP_THRESHOLD_HIGH) {
    autoFanOverrideON = false;
    if (!fanState && !autoFanOverrideOFF) {
      setFan(true);
      startBeep(1500);
    }
  } else if (temp <= TEMP_THRESHOLD_LOW) {
    autoFanOverrideOFF = false;
    if (fanState && !autoFanOverrideON) setFan(false);
  }
}

void sendFullStatus() {
  if (!SerialBT.hasClient()) return;
  String message = "STATUS|";
  message += sensorOK ? String(temp, 1) : String("--.-");
  message += lightState ? "|1" : "|0";
  message += fanState ? "|1" : "|0";
  message += isAutoMode ? "|1" : "|0";
  const bool openSide = curtainStatus == C_OPEN || curtainStatus == C_OPENING;
  message += openSide ? "|1" : "|0";
  SerialBT.println(message);
}

void serviceStatus() {
  if (!SerialBT.hasClient()) return;
  const uint32_t now = millis();
  const uint32_t elapsed = (uint32_t)(now - lastStatusSent);
  if (statusRequested || (statusPending && elapsed >= STATUS_CHANGE_INTERVAL) ||
      elapsed >= STATUS_HEARTBEAT_INTERVAL) {
    sendFullStatus();
    lastStatusSent = millis();
    statusPending = false;
    statusRequested = false;
  }
}

void setup() {
  Serial.begin(115200);
  digitalWrite(RELAY_LIGHT, RELAY_OFF);
  digitalWrite(RELAY_FAN, RELAY_OFF);
  digitalWrite(RELAY_CURTAIN_OPEN, RELAY_OFF);
  digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_OFF);
  digitalWrite(BUZZER_PIN, LOW);
  pinMode(RELAY_LIGHT, OUTPUT);
  pinMode(RELAY_FAN, OUTPUT);
  pinMode(RELAY_CURTAIN_OPEN, OUTPUT);
  pinMode(RELAY_CURTAIN_CLOSE, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  stopCurtainRelays();

  nvsReady = preferences.begin("nexus", false);
  if (!nvsReady) Serial.println("NVS OPEN FAILED: persistence unavailable");
  if (nvsReady) {
    lightState = preferences.getBool("light_state", false);
    fanState = preferences.getBool("fan_state", false);
    isAutoMode = preferences.getBool("auto_mode", false);
  }
  savedLight = lightState;
  savedFan = fanState;
  savedAuto = isAutoMode;
  digitalWrite(RELAY_LIGHT, lightState ? RELAY_ON : RELAY_OFF);
  digitalWrite(RELAY_FAN, fanState ? RELAY_ON : RELAY_OFF);

  VC02.begin(9600, SERIAL_8N1, VC_RX, VC_TX);
  if (!SerialBT.begin("NexusHome")) Serial.println("BLUETOOTH START FAILED");
  dht.begin();
  Wire.begin(21, 22);
  display.setBusClock(400000);
  display.begin();
  display.setContrast(220);
  display.setFontPosTop();
  bootStartedAt = millis();
  lastSensorRead = bootStartedAt;
  lastDisplayFrame = bootStartedAt - DISPLAY_INTERVAL;

  int savedState = nvsReady ? preferences.getInt("curtain_state", C_CLOSED) : C_CLOSED;
  uint32_t savedElapsed = nvsReady ? preferences.getUInt("c_elapsed", 0) : 0;
  if (savedState < C_CLOSED || savedState > C_CLOSING) {
    Serial.println("NVS: invalid curtain state; using CLOSED estimate");
    savedState = C_CLOSED;
    savedElapsed = 0;
  }
  if (savedElapsed > CURTAIN_MOVE_TIME) savedElapsed = CURTAIN_MOVE_TIME;
  curtainStatus = static_cast<CurtainState>(savedState);
  if (curtainStatus == C_OPENING || curtainStatus == C_CLOSING) {
    if (savedElapsed >= CURTAIN_MOVE_TIME) {
      curtainStatus = curtainStatus == C_OPENING ? C_OPEN : C_CLOSED;
      saveCurtainSnapshot(0);
    } else {
      curtainElapsed = savedElapsed;
      curtainProgress = savedElapsed;
      curtainMoveStartedAt = millis();
      lastCurtainSave = curtainMoveStartedAt;
      digitalWrite(curtainStatus == C_OPENING ? RELAY_CURTAIN_OPEN : RELAY_CURTAIN_CLOSE,
                   RELAY_ON);
    }
  }
  drawSplash(bootStartedAt);
  startBeep(120);
}

void loop() {
  serviceBuzzer();
  serviceCurtain();
  serviceVoice();
  serviceBluetoothInput();
  serviceAutoFan();
  serviceCurtain();
  serviceDisplay();
  // Potentially blocking sensor work occurs after controls/display get a turn.
  serviceSensor();
  serviceAutoFan();
  serviceCurtain();
  serviceBuzzer();
  serviceDisplay();
  serviceStatus();
  serviceCurtain();
}

void serviceDisplay() {
  const uint32_t now = millis();
  if ((uint32_t)(now - lastDisplayFrame) < DISPLAY_INTERVAL) return;
  lastDisplayFrame = now;
  if (splashActive) {
    if ((uint32_t)(now - bootStartedAt) < SPLASH_TIME) {
      drawSplash(now);
      return;
    }
    splashActive = false;
    displayDirty = true;
  }
  const bool animationActive = curtainStatus == C_OPENING || curtainStatus == C_CLOSING ||
                               tempInitializing(now);
  const bool heartbeatDue = (uint32_t)(now - lastHeartbeatFrame) >= HEARTBEAT_INTERVAL;
  if (displayDirty || animationActive || heartbeatDue) {
    drawDashboard(now);
    displayDirty = false;
    if (heartbeatDue) lastHeartbeatFrame = now;
  }
}

void drawSplash(uint32_t now) {
  display.clearBuffer();
  display.drawRFrame(3, 3, 122, 58, 7);
  display.drawRFrame(6, 6, 116, 52, 5);
  display.setFont(u8g2_font_logisoso16_tf);
  const char *title = "NexusHome";
  display.drawStr((128 - display.getStrWidth(title)) / 2, 14, title);
  display.setFont(u8g2_font_5x8_tf);
  const char *sub = "INITIALIZING";
  display.drawStr((128 - display.getStrWidth(sub)) / 2, 38, sub);
  const uint8_t phase = ((now - bootStartedAt) / 220) % 4;
  for (uint8_t i = 0; i < 3; ++i) {
    if (i < phase) display.drawDisc(102 + i * 6, 42, 1);
    else display.drawCircle(102 + i * 6, 42, 1);
  }
  display.drawFrame(22, 51, 84, 4);
  uint32_t elapsed = now - bootStartedAt;
  if (elapsed > SPLASH_TIME) elapsed = SPLASH_TIME;
  const uint8_t fill = (uint32_t)82 * elapsed / SPLASH_TIME;
  if (fill > 0) display.drawBox(23, 52, fill, 2);
  display.sendBuffer();
}

void drawDashboard(uint32_t now) {
  display.clearBuffer();
  display.setDrawColor(1);
  display.drawRBox(0, 0, 128, 13, 2);
  display.setDrawColor(0);
  display.setFont(u8g2_font_5x8_tr);
  display.drawStr(4, 3, "NEXUS");
  const char *mode = isAutoMode ? "AUTO" : "MANUAL";
  display.drawStr(64 - display.getStrWidth(mode) / 2, 3, mode);
  const bool pulse = ((now / 500) & 1U) == 0;
  if (sensorOK && pulse) display.drawDisc(120, 6, 2);
  else display.drawCircle(120, 6, 2);
  display.setDrawColor(1);

  if (!sensorOK && dhtFailCount >= 3) {
    display.setFont(u8g2_font_helvB10_tf);
    const char *label = "SENSOR ERROR";
    display.drawStr((128 - display.getStrWidth(label)) / 2, 17, label);
    display.setFont(u8g2_font_5x8_tr);
    const char *retry = "RETRYING...";
    display.drawStr((128 - display.getStrWidth(retry)) / 2, 33, retry);
  } else if (tempInitializing(now)) {
    display.setFont(u8g2_font_helvB10_tf);
    const char *label = "INITIALIZING";
    display.drawStr((128 - display.getStrWidth(label)) / 2, 17, label);
    const uint8_t phase = (now / 220) % 4;
    for (uint8_t i = 0; i < 3; ++i) {
      const int x = 57 + i * 6;
      if (i < phase) display.drawDisc(x, 35, 2);
      else display.drawCircle(x, 35, 2);
    }
  } else {
    char tempText[12];
    dtostrf(temp, 4, 1, tempText);
    display.setFont(u8g2_font_logisoso24_tf);
    const int tempWidth = display.getStrWidth(tempText);
    display.setFont(u8g2_font_helvB10_tf);
    const int unitWidth = display.getStrWidth("\xb0" "C");
    const int tempStart = (128 - tempWidth - unitWidth - 3) / 2;
    display.setFont(u8g2_font_logisoso24_tf);
    display.drawStr(tempStart, 15, tempText);
    display.setFont(u8g2_font_helvB10_tf);
    display.drawStr(tempStart + tempWidth + 3, 18, "\xb0" "C");
  }

  display.drawHLine(0, 44, 128);
  display.drawVLine(42, 47, 15);
  display.drawVLine(85, 47, 15);
  display.setFont(u8g2_font_5x8_tr);
  display.drawStr(21 - display.getStrWidth("LIGHT") / 2, 47, "LIGHT");
  const char *lightValue = lightState ? "ON" : "OFF";
  display.drawStr(21 - display.getStrWidth(lightValue) / 2, 56, lightValue);
  display.drawStr(64 - display.getStrWidth("FAN") / 2, 47, "FAN");
  const char *fanValue = fanState ? "ON" : "OFF";
  display.drawStr(64 - display.getStrWidth(fanValue) / 2, 56, fanValue);
  display.drawStr(106 - display.getStrWidth("CURTAIN") / 2, 47, "CURTAIN");
  if (curtainStatus == C_OPENING || curtainStatus == C_CLOSING) {
    const int barX = 88, barY = 57, barW = 36, barH = 5;
    display.drawRFrame(barX, barY, barW, barH, 1);
    int fill = (uint32_t)(barW - 2) * curtainProgress / CURTAIN_MOVE_TIME;
    if (fill > barW - 2) fill = barW - 2;
    if (curtainStatus == C_CLOSING) fill = barW - 2 - fill;
    if (fill > 0) display.drawBox(barX + 1, barY + 1, fill, barH - 2);
  } else {
    const char *value = curtainStatus == C_OPEN ? "OPEN" : "CLOSED";
    display.drawStr(106 - display.getStrWidth(value) / 2, 56, value);
  }
  display.sendBuffer();
}
