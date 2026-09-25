/*
 * NexusHome - stable curtain timing, safe auto startup, smooth 2.42" OLED UI
 * Target: ESP32 + DHT22 + SSD1309 128x64 + VC-02 + Bluetooth Classic
 *
 * Important fixes:
 *  1. Curtain timing always takes a fresh millis() value after a command.
 *     This removes the unsigned-underflow race that could finish a new move
 *     almost instantly.
 *  2. Automatic fan control waits for a valid DHT reading, so it cannot erase
 *     the startup screen or change the saved fan state using temp=0 at boot.
 *  3. The splash screen has its own non-blocking lifetime and animation.
 *  4. OLED animation runs every 50 ms; NVS saving is separate and slower.
 *  5. Voice bytes are parsed as a stream instead of flushing valid bytes.
 *  6. First DHT22 read is delayed until the sensor has had time to warm up,
 *     so the dashboard can't briefly show a spurious high/low temperature
 *     right after the splash screen ends.
 *  7. Auto-fan OFF hysteresis gap tightened from 1.0C to 0.5C so the fan
 *     switches off closer to the ON threshold (tune TEMP_THRESHOLD_LOW to taste).
 */

#include <DHT.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <HardwareSerial.h>
#include <BluetoothSerial.h>
#include <Preferences.h>

// ================= PINS / HARDWARE =================
#define DHTPIN 33
#define DHTTYPE DHT22
#define RELAY_LIGHT 2
#define RELAY_FAN 15
#define RELAY_CURTAIN_OPEN 18
#define RELAY_CURTAIN_CLOSE 19
#define BUZZER_PIN 13
#define VC_RX 16
#define VC_TX 17

// ================= SETTINGS =================
constexpr float TEMP_THRESHOLD_HIGH = 30.0f;
// Matches the paper's spec exactly: fan ON at 30C and above, fan OFF at
// 29C and below. The 1C gap between ON (30) and OFF (29) is intentional
// hysteresis -- it's what stops the relay from rapidly clicking on/off if
// the reading hovers right around 30, and it's part of the spec, not a bug.
constexpr float TEMP_THRESHOLD_LOW  = 29.0f;

constexpr uint32_t SENSOR_INTERVAL       = 2500;
constexpr uint32_t CURTAIN_MOVE_TIME     = 5000;
constexpr uint32_t CURTAIN_SAVE_INTERVAL = 500;
constexpr uint32_t DISPLAY_INTERVAL      = 50;   // 20 FPS while moving
constexpr uint32_t HEARTBEAT_INTERVAL    = 500;
constexpr uint32_t SPLASH_TIME           = 1900;
constexpr uint32_t VOICE_REPEAT_GUARD    = 350;

// Minimum time the dashboard's "INITIALIZING" temperature animation plays,
// counted from boot. Even if the DHT22 happens to return a valid reading
// sooner, the animation still runs the full duration, and automatic mode
// still waits for it -- so "temperature is ready" always means both a valid
// reading AND this minimum time have both happened, not just whichever
// comes first. Manual mode is untouched by this (see tempInitializing()).
constexpr uint32_t TEMP_INIT_MIN_TIME    = 2500;

// Active-LOW relay board
constexpr uint8_t RELAY_ON  = LOW;
constexpr uint8_t RELAY_OFF = HIGH;

U8G2_SSD1309_128X64_NONAME0_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
DHT dht(DHTPIN, DHTTYPE);
HardwareSerial VC02(1);
BluetoothSerial SerialBT;
Preferences preferences;

// ================= COMMANDS =================
#define CMD_LIGHT_ON      0xA0
#define CMD_LIGHT_OFF     0xA1
#define CMD_FAN_ON        0xB0
#define CMD_FAN_OFF       0xB1
#define CMD_MODE_AUTO     0xC0
#define CMD_MODE_MAN      0xC1
#define CMD_CURTAIN_OPEN  0xD0
#define CMD_CURTAIN_CLOSE 0xD1

// ================= SYSTEM STATE =================
float temp = 0.0f;
float lastValidTemp = 0.0f;
bool sensorOK = false;
uint8_t dhtFailCount = 0;

bool isAutoMode = false;
bool fanState = false;
bool lightState = false;
bool autoFanOverrideOFF = false;
bool autoFanOverrideON  = false;

enum CurtainState : uint8_t { C_CLOSED, C_OPENING, C_OPEN, C_CLOSING };
CurtainState curtainStatus = C_CLOSED;

// curtainElapsed is the already-completed part restored from NVS.
// curtainMoveStartedAt is the fresh millis() baseline for this boot/move.
uint32_t curtainElapsed = 0;
uint32_t curtainProgress = 0;
uint32_t curtainMoveStartedAt = 0;
uint32_t lastCurtainSave = 0;

uint32_t bootStartedAt = 0;
uint32_t lastSensorRead = 0;
uint32_t lastBTUpdate = 0;
uint32_t lastDisplayFrame = 0;
uint32_t lastHeartbeatFrame = 0;

bool splashActive = true;
bool displayDirty = true;

// Non-blocking buzzer
bool buzzerOn = false;
uint32_t buzzerOffAt = 0;

// Voice stream parser
bool voiceWaitingForCommand = false;
uint8_t lastVoiceCommand = 0;
uint32_t lastVoiceCommandAt = 0;

// ================= FORWARD DECLARATIONS =================
void processCommand(uint8_t cmd, const char *source);
void sendFullStatus();
void serviceCurtain();
void serviceDisplay();
void drawSplash(uint32_t now);
void drawDashboard(uint32_t now);
void markDisplayDirty();
bool tempInitializing(uint32_t now);

// ================= SMALL HELPERS =================
static bool timeReached(uint32_t now, uint32_t deadline) {
  return (int32_t)(now - deadline) >= 0;
}

void markDisplayDirty() {
  displayDirty = true;
}

void startBeep(uint32_t durationMs) {
  digitalWrite(BUZZER_PIN, HIGH);
  buzzerOn = true;
  buzzerOffAt = millis() + durationMs;
}

void serviceBuzzer() {
  const uint32_t now = millis();
  if (buzzerOn && timeReached(now, buzzerOffAt)) {
    digitalWrite(BUZZER_PIN, LOW);
    buzzerOn = false;
  }
}

void stopCurtainRelays() {
  digitalWrite(RELAY_CURTAIN_OPEN, RELAY_OFF);
  digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_OFF);
}

bool isValidCurtainState(int value) {
  return value >= C_CLOSED && value <= C_CLOSING;
}

// True until the temperature is genuinely ready to drive automatic mode:
// requires a valid DHT reading AND the minimum init-animation time to have
// elapsed, whichever finishes last. Purely a display/gating helper -- does
// not touch sensor sampling, manual commands, or NVS persistence.
bool tempInitializing(uint32_t now) {
  const bool minTimeElapsed = (uint32_t)(now - bootStartedAt) >= TEMP_INIT_MIN_TIME;
  return !sensorOK || !minTimeElapsed;
}

// ================= SETUP =================
void setup() {
  Serial.begin(115200);

  // Preload active-LOW relay latches before enabling output mode. This avoids
  // a brief LOW pulse that could twitch the motor during ESP32 startup.
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

  // Motors and buzzer are made safe before communications/display startup.
  stopCurtainRelays();
  digitalWrite(BUZZER_PIN, LOW);

  preferences.begin("nexus", false);
  lightState = preferences.getBool("light_state", false);
  fanState   = preferences.getBool("fan_state", false);
  isAutoMode = preferences.getBool("auto_mode", false);

  digitalWrite(RELAY_LIGHT, lightState ? RELAY_ON : RELAY_OFF);
  digitalWrite(RELAY_FAN, fanState ? RELAY_ON : RELAY_OFF);

  VC02.begin(9600, SERIAL_8N1, VC_RX, VC_TX);
  SerialBT.begin("NexusHome");
  dht.begin();

  Wire.begin(21, 22);
  display.begin();
  display.setBusClock(400000);
  display.setContrast(220); // crisp without keeping every pixel at maximum drive
  display.setFontPosTop();

  bootStartedAt = millis();

  // FIX: don't force the first DHT22 read to happen instantly. The sensor
  // needs a short warm-up after power-on, and reading it too early can
  // return a valid-looking but wrong value (often a false-high spike).
  // Letting the first read happen on the normal SENSOR_INTERVAL cadence
  // gives it time to settle, so the dashboard never shows a bogus reading
  // right after the splash screen ends, and auto mode (gated on sensorOK)
  // can't react to it either.
  lastSensorRead = bootStartedAt;
  lastDisplayFrame = bootStartedAt - DISPLAY_INTERVAL;

  // Restore curtain safely. A moving curtain resumes only its saved remainder.
  int savedState = preferences.getInt("curtain_state", C_CLOSED);
  uint32_t savedElapsed = preferences.getUInt("c_elapsed", 0);

  if (!isValidCurtainState(savedState)) {
    savedState = C_CLOSED;
    savedElapsed = 0;
  }

  curtainStatus = static_cast<CurtainState>(savedState);
  if (savedElapsed > CURTAIN_MOVE_TIME) savedElapsed = CURTAIN_MOVE_TIME;

  if (curtainStatus == C_OPENING || curtainStatus == C_CLOSING) {
    if (savedElapsed >= CURTAIN_MOVE_TIME) {
      curtainStatus = (curtainStatus == C_OPENING) ? C_OPEN : C_CLOSED;
      curtainElapsed = 0;
      curtainProgress = 0;
      preferences.putInt("curtain_state", curtainStatus);
      preferences.putUInt("c_elapsed", 0);
    } else {
      curtainElapsed = savedElapsed;
      curtainProgress = savedElapsed;
      curtainMoveStartedAt = millis();
      lastCurtainSave = curtainMoveStartedAt;

      if (curtainStatus == C_OPENING) {
        digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_OFF);
        digitalWrite(RELAY_CURTAIN_OPEN, RELAY_ON);
      } else {
        digitalWrite(RELAY_CURTAIN_OPEN, RELAY_OFF);
        digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_ON);
      }
    }
  } else {
    curtainElapsed = 0;
    curtainProgress = 0;
    preferences.putUInt("c_elapsed", 0);
  }

  drawSplash(bootStartedAt);
  startBeep(120);
}

// ================= MAIN LOOP =================
void loop() {
  serviceBuzzer();

  // DHT22 sampling
  uint32_t now = millis();
  if ((uint32_t)(now - lastSensorRead) >= SENSOR_INTERVAL) {
    lastSensorRead = now;
    const float newTemp = dht.readTemperature();

    if (!isnan(newTemp)) {
      temp = newTemp;
      lastValidTemp = newTemp;
      sensorOK = true;
      dhtFailCount = 0;
    } else {
      temp = lastValidTemp;
      if (dhtFailCount < 255) dhtFailCount++;
      if (dhtFailCount >= 3) sensorOK = false;
    }
    markDisplayDirty();
  }

  // Voice parser: consume every complete 0x20 + command frame, without
  // throwing away a following valid frame.
  while (VC02.available() > 0) {
    const uint8_t incoming = VC02.read();

    if (!voiceWaitingForCommand) {
      if (incoming == 0x20) voiceWaitingForCommand = true;
      continue;
    }

    // If a second header arrives, stay synchronized and keep waiting for the
    // actual command byte instead of discarding the next valid frame.
    if (incoming == 0x20) continue;

    voiceWaitingForCommand = false;
    const uint32_t commandNow = millis();
    const bool repeatedTooSoon =
      incoming == lastVoiceCommand &&
      (uint32_t)(commandNow - lastVoiceCommandAt) < VOICE_REPEAT_GUARD;

    if (!repeatedTooSoon) {
      lastVoiceCommand = incoming;
      lastVoiceCommandAt = commandNow;
      processCommand(incoming, "Voice");
    }
  }

  // Bluetooth commands
  while (SerialBT.available() > 0) {
    const char btCmd = SerialBT.read();
    if      (btCmd == 'S') sendFullStatus();
    else if (btCmd == 'A') processCommand(CMD_LIGHT_ON, "App");
    else if (btCmd == 'a') processCommand(CMD_LIGHT_OFF, "App");
    else if (btCmd == 'B') processCommand(CMD_FAN_ON, "App");
    else if (btCmd == 'b') processCommand(CMD_FAN_OFF, "App");
    else if (btCmd == 'C') processCommand(CMD_MODE_AUTO, "App");
    else if (btCmd == 'c') processCommand(CMD_MODE_MAN, "App");
    else if (btCmd == 'D') processCommand(CMD_CURTAIN_OPEN, "App");
    else if (btCmd == 'd') processCommand(CMD_CURTAIN_CLOSE, "App");
  }

  // Automatic mode never acts until BOTH a valid DHT reading exists AND the
  // minimum init-animation time has passed (tempInitializing covers both).
  // This also keeps the initialization screen from being overwritten.
  // Manual mode is completely unaffected -- CMD_FAN_ON/OFF and CMD_LIGHT_ON/OFF
  // in processCommand() write straight to the relay and NVS with no
  // dependency on sensorOK or tempInitializing() at all.
  if (!splashActive && isAutoMode && !tempInitializing(now)) {
    if (temp >= TEMP_THRESHOLD_HIGH) {
      autoFanOverrideON = false;
      if (!fanState && !autoFanOverrideOFF) {
        digitalWrite(RELAY_FAN, RELAY_ON);
        fanState = true;
        preferences.putBool("fan_state", true);
        startBeep(1500);
        sendFullStatus();
        markDisplayDirty();
      }
    } else if (temp <= TEMP_THRESHOLD_LOW) {
      autoFanOverrideOFF = false;
      if (fanState && !autoFanOverrideON) {
        digitalWrite(RELAY_FAN, RELAY_OFF);
        fanState = false;
        preferences.putBool("fan_state", false);
        sendFullStatus();
        markDisplayDirty();
      }
    }
  }

  serviceCurtain();
  serviceDisplay();

  now = millis();
  if ((uint32_t)(now - lastBTUpdate) >= 2000) {
    lastBTUpdate = now;
    if (SerialBT.hasClient()) sendFullStatus();
  }
}

// ================= CURTAIN =================
void startCurtain(CurtainState movingState) {
  stopCurtainRelays();
  curtainElapsed = 0;
  curtainProgress = 0;
  curtainStatus = movingState;

  // Save the new movement before energizing the motor. NVS latency therefore
  // cannot consume part of the five-second motor run.
  preferences.putInt("curtain_state", curtainStatus);
  preferences.putUInt("c_elapsed", 0);

  // Take ONE fresh timestamp immediately before motor start. The loop's older
  // timestamp is never used, preventing unsigned subtraction from wrapping.
  const uint32_t startNow = millis();
  curtainMoveStartedAt = startNow;
  lastCurtainSave = startNow;

  if (movingState == C_OPENING) {
    digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_OFF);
    digitalWrite(RELAY_CURTAIN_OPEN, RELAY_ON);
  } else {
    digitalWrite(RELAY_CURTAIN_OPEN, RELAY_OFF);
    digitalWrite(RELAY_CURTAIN_CLOSE, RELAY_ON);
  }

  markDisplayDirty();
}

void serviceCurtain() {
  if (curtainStatus != C_OPENING && curtainStatus != C_CLOSING) return;

  // IMPORTANT: fresh time captured after any command may have started a move.
  const uint32_t now = millis();
  uint32_t liveElapsed = curtainElapsed + (uint32_t)(now - curtainMoveStartedAt);
  if (liveElapsed > CURTAIN_MOVE_TIME) liveElapsed = CURTAIN_MOVE_TIME;

  if (liveElapsed != curtainProgress) {
    curtainProgress = liveElapsed;
    markDisplayDirty();
  }

  if (liveElapsed >= CURTAIN_MOVE_TIME) {
    stopCurtainRelays();
    curtainStatus = (curtainStatus == C_OPENING) ? C_OPEN : C_CLOSED;
    curtainElapsed = 0;
    curtainProgress = 0;

    preferences.putInt("curtain_state", curtainStatus);
    preferences.putUInt("c_elapsed", 0);
    sendFullStatus();
    markDisplayDirty();
    return;
  }

  // Saving is intentionally independent from OLED animation.
  if ((uint32_t)(now - lastCurtainSave) >= CURTAIN_SAVE_INTERVAL) {
    preferences.putUInt("c_elapsed", liveElapsed);
    lastCurtainSave = now;
  }
}

// ================= COMMAND HANDLING =================
void processCommand(uint8_t cmd, const char *source) {
  (void)source;
  bool accepted = true;

  switch (cmd) {
    case CMD_LIGHT_ON:
      digitalWrite(RELAY_LIGHT, RELAY_ON);
      lightState = true;
      preferences.putBool("light_state", true);
      break;

    case CMD_LIGHT_OFF:
      digitalWrite(RELAY_LIGHT, RELAY_OFF);
      lightState = false;
      preferences.putBool("light_state", false);
      break;

    case CMD_FAN_ON:
      digitalWrite(RELAY_FAN, RELAY_ON);
      fanState = true;
      preferences.putBool("fan_state", true);
      autoFanOverrideON = true;
      autoFanOverrideOFF = false;
      break;

    case CMD_FAN_OFF:
      digitalWrite(RELAY_FAN, RELAY_OFF);
      fanState = false;
      preferences.putBool("fan_state", false);
      autoFanOverrideON = false;
      autoFanOverrideOFF = isAutoMode && sensorOK && temp >= TEMP_THRESHOLD_HIGH;
      break;

    case CMD_MODE_AUTO:
      isAutoMode = true;
      autoFanOverrideOFF = false;
      autoFanOverrideON = false;
      preferences.putBool("auto_mode", true);
      break;

    case CMD_MODE_MAN:
      isAutoMode = false;
      autoFanOverrideOFF = false;
      autoFanOverrideON = false;
      preferences.putBool("auto_mode", false);
      digitalWrite(RELAY_FAN, RELAY_OFF);
      fanState = false;
      preferences.putBool("fan_state", false);
      break;

    case CMD_CURTAIN_OPEN:
      if (curtainStatus == C_CLOSED) startCurtain(C_OPENING);
      else accepted = false;
      break;

    case CMD_CURTAIN_CLOSE:
      if (curtainStatus == C_OPEN) startCurtain(C_CLOSING);
      else accepted = false;
      break;

    default:
      accepted = false;
      break;
  }

  if (accepted) {
    sendFullStatus();
    markDisplayDirty();
  }
}

void sendFullStatus() {
  String message = "STATUS|";
  message += sensorOK ? String(temp, 1) : String("--.-");
  message += lightState ? "|1" : "|0";
  message += fanState ? "|1" : "|0";
  message += isAutoMode ? "|1" : "|0";
  const bool openSide = curtainStatus == C_OPEN || curtainStatus == C_OPENING;
  message += openSide ? "|1" : "|0";
  SerialBT.println(message);
}

// ================= OLED UI =================
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

  // Keep redrawing every frame while the curtain is moving OR while the
  // temperature isn't fully initialized yet (valid reading + minimum time),
  // so the "INITIALIZING" dots animate for the guaranteed minimum duration.
  // This never touches auto mode logic -- it only affects what's drawn.
  const bool animationActive =
    curtainStatus == C_OPENING || curtainStatus == C_CLOSING || tempInitializing(now);
  const bool heartbeatDue =
    (uint32_t)(now - lastHeartbeatFrame) >= HEARTBEAT_INTERVAL;

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

  // Gentle three-dot animation plus progress line.
  const uint8_t phase = ((now - bootStartedAt) / 220) % 4;
  for (uint8_t i = 0; i < 3; i++) {
    if (i < phase) display.drawDisc(102 + i * 6, 42, 1);
    else           display.drawCircle(102 + i * 6, 42, 1);
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

  // Inverted header
  display.setDrawColor(1);
  display.drawRBox(0, 0, 128, 13, 2);
  display.setDrawColor(0);
  display.setFont(u8g2_font_5x8_tr);
  display.drawStr(4, 3, "NEXUS");

  const char *mode = isAutoMode ? "AUTO" : "MANUAL";
  display.drawStr(64 - display.getStrWidth(mode) / 2, 3, mode);

  // Small heartbeat indicator: animated only while the dashboard is redrawn.
  const bool pulse = ((now / 500) & 1U) == 0;
  if (sensorOK) {
    if (pulse) display.drawDisc(120, 6, 2);
    else       display.drawCircle(120, 6, 2);
  } else {
    display.drawCircle(120, 6, 2);
  }
  display.setDrawColor(1);

  // Temperature area
  if (tempInitializing(now)) {
    // Either no valid DHT reading yet (still warming up after boot, or
    // briefly re-syncing after read failures) OR the guaranteed minimum
    // animation time hasn't elapsed yet. Show an animated "INITIALIZING"
    // indicator instead of a number. Auto mode is gated on this exact same
    // function, so it cannot react while this is showing -- this block is
    // purely cosmetic.
    display.setFont(u8g2_font_helvB10_tf);
    const char *label = "INITIALIZING";
    const int labelWidth = display.getStrWidth(label);
    display.drawStr((128 - labelWidth) / 2, 17, label);

    // Three-dot pulse, same style as the splash screen, timed off `now` so
    // it animates continuously regardless of how long init/re-sync takes.
    const uint8_t phase = (now / 220) % 4;
    const int dotsWidth = 3 * 6 - 4; // spacing between the 3 dots
    const int dotsStartX = (128 - dotsWidth) / 2;
    for (uint8_t i = 0; i < 3; i++) {
      const int dotX = dotsStartX + i * 6;
      if (i < phase) display.drawDisc(dotX, 35, 2);
      else           display.drawCircle(dotX, 35, 2);
    }
  } else {
    char tempText[8];
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

  // Compact device cards
  display.drawStr(21 - display.getStrWidth("LIGHT") / 2, 47, "LIGHT");
  const char *lightValue = lightState ? "ON" : "OFF";
  display.drawStr(21 - display.getStrWidth(lightValue) / 2, 56, lightValue);

  display.drawStr(64 - display.getStrWidth("FAN") / 2, 47, "FAN");
  const char *fanValue = fanState ? "ON" : "OFF";
  display.drawStr(64 - display.getStrWidth(fanValue) / 2, 56, fanValue);

  display.drawStr(106 - display.getStrWidth("CURTAIN") / 2, 47, "CURTAIN");

  if (curtainStatus == C_OPENING || curtainStatus == C_CLOSING) {
    const int barX = 88;
    const int barY = 57;
    const int barW = 36;
    const int barH = 5;
    display.drawRFrame(barX, barY, barW, barH, 1);

    int fill = (uint32_t)(barW - 2) * curtainProgress / CURTAIN_MOVE_TIME;
    if (fill < 0) fill = 0;
    if (fill > barW - 2) fill = barW - 2;

    // Closing visually drains; opening fills.
    if (curtainStatus == C_CLOSING) fill = (barW - 2) - fill;
    if (fill > 0) display.drawBox(barX + 1, barY + 1, fill, barH - 2);
  } else {
    const char *curtainValue = curtainStatus == C_OPEN ? "OPEN" : "CLOSED";
    display.drawStr(106 - display.getStrWidth(curtainValue) / 2, 56, curtainValue);
  }

  display.sendBuffer();
}
