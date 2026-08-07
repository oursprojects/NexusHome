/*
 * Project: NexusHome - Long Alert Edition
 * Features: 
 * - Auto Mode Trigger = 1.5 Second Beep
 * - Auto Force-ON Logic
 * - Improved Smart UI (clean layout, 3-column data)
 * - 4-Relay Channel Layout (No limit switches)
 * - Timer-based Curtain Control (5 Seconds)
 * - State Machine Error Handling (Prevents command overlap)
 * - Preferences Save State (Full state recovery for Light, Fan, Mode)
 * - Curtain Partial Resume (Saves exact milliseconds every 200ms before blackout)
 * - Dynamic Bluetooth Connection Indicator
 */

#include <DHT.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <HardwareSerial.h>
#include <BluetoothSerial.h>
#include <Preferences.h> 

// ================= CONFIGURATION =================
#define DHTPIN 33           
#define RELAY_LIGHT 2      
#define RELAY_FAN 15       

#define RELAY_CURTAIN_OPEN  18  
#define RELAY_CURTAIN_CLOSE 19  

#define BUZZER_PIN 13      
#define VC_RX 16           
#define VC_TX 17           

#define DHTTYPE DHT22
#define TEMP_THRESHOLD_HIGH 30.0  
#define TEMP_THRESHOLD_LOW  29.0  
#define SENSOR_INTERVAL 2000      
#define CURTAIN_MOVE_TIME 5000 

U8G2_SSD1309_128X64_NONAME0_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);

// --- VOICE COMMAND HEX CODES ---
#define CMD_LIGHT_ON   0xA0
#define CMD_LIGHT_OFF  0xA1
#define CMD_FAN_ON     0xB0
#define CMD_FAN_OFF    0xB1
#define CMD_MODE_AUTO  0xC0
#define CMD_MODE_MAN   0xC1
#define CMD_CURTAIN_OPEN  0xD0
#define CMD_CURTAIN_CLOSE 0xD1

// ================= OBJECTS & STATES =================
DHT dht(DHTPIN, DHTTYPE);
HardwareSerial VC02(1);   
BluetoothSerial SerialBT; 
Preferences preferences; 

float temp = 0.0;
float lastValidTemp = 0.0; 
bool isAutoMode = false;  
bool fanState = false;
bool lightState = false;

// Curtain State Machine & Timers
enum CurtainState { C_CLOSED, C_OPENING, C_OPEN, C_CLOSING };
CurtainState curtainStatus = C_CLOSED; 
unsigned long curtainMoveTimer = 0;
uint32_t curtainElapsed = 0;      
unsigned long lastFlashUpdate = 0; 

bool autoFanOverrideOFF = false; 
bool autoFanOverrideON = false;  

unsigned long lastSensorRead = 0;
unsigned long lastBTUpdate = 0;

// ================= SETUP =================
void setup() {
  Serial.begin(115200);                    
  VC02.begin(9600, SERIAL_8N1, VC_RX, VC_TX); 
  SerialBT.begin("NexusHome"); 

  // --- LOAD SAVED STATES FROM MEMORY ---
  preferences.begin("nexus", false); 
  lightState = preferences.getBool("light_state", false);
  fanState = preferences.getBool("fan_state", false);
  isAutoMode = preferences.getBool("auto_mode", false);
  
  int savedCurtainState = preferences.getInt("curtain_state", C_CLOSED);
  uint32_t savedElapsed = preferences.getUInt("c_elapsed", 0);

  pinMode(RELAY_LIGHT, OUTPUT);
  pinMode(RELAY_FAN, OUTPUT);
  pinMode(RELAY_CURTAIN_OPEN, OUTPUT);
  pinMode(RELAY_CURTAIN_CLOSE, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT); 
  
  // Apply saved states immediately (Assuming LOW is ON for relays)
  digitalWrite(RELAY_LIGHT, lightState ? LOW : HIGH); 
  digitalWrite(RELAY_FAN, fanState ? LOW : HIGH);   
  digitalWrite(RELAY_CURTAIN_OPEN, HIGH);  
  digitalWrite(RELAY_CURTAIN_CLOSE, HIGH); 
  digitalWrite(BUZZER_PIN, LOW);   

  // --- RESUME CURTAIN IF IT WAS MOVING DURING BLACKOUT ---
  curtainStatus = (CurtainState)savedCurtainState;
  if (curtainStatus == C_OPENING || curtainStatus == C_CLOSING) {
    curtainElapsed = savedElapsed;
    if (curtainElapsed >= CURTAIN_MOVE_TIME) {
      // Failsafe if it somehow booted with full time elapsed
      curtainStatus = (curtainStatus == C_OPENING) ? C_OPEN : C_CLOSED;
      curtainElapsed = 0;
    } else {
      curtainMoveTimer = millis(); 
      if (curtainStatus == C_OPENING) digitalWrite(RELAY_CURTAIN_OPEN, LOW);
      else digitalWrite(RELAY_CURTAIN_CLOSE, LOW);
    }
  }

  dht.begin();
  Wire.begin(21, 22);

  display.begin();
  display.setBusClock(400000);
  display.setContrast(255);
  display.setFontPosTop(); 
  
  display.clearBuffer();
  display.drawRFrame(4, 4, 120, 56, 5); 
  display.setFont(u8g2_font_logisoso16_tf);
  display.drawStr((128 - display.getStrWidth("NexusHome")) / 2, 28, "NexusHome");
  display.setFont(u8g2_font_5x8_tf);
  display.drawStr((128 - display.getStrWidth("INITIALIZING...")) / 2, 48, "INITIALIZING...");
  display.sendBuffer();
  
  digitalWrite(BUZZER_PIN, HIGH);
  delay(100);
  digitalWrite(BUZZER_PIN, LOW);
  delay(2000); 
}

// ================= MAIN LOOP =================
void loop() {
  unsigned long currentMillis = millis();

  // Sensor Reading
  if (currentMillis - lastSensorRead >= SENSOR_INTERVAL) {
    lastSensorRead = currentMillis;
    float newTemp = dht.readTemperature();
    if (!isnan(newTemp) && newTemp != 0.0) {
      temp = newTemp;
      lastValidTemp = newTemp;
    } else temp = lastValidTemp; 
    updateDisplay();
  }

  // Voice Commands
  if (VC02.available() >= 2) {
    if (VC02.read() == 0x20) processCommand(VC02.read(), "Voice");
    else while(VC02.available()) VC02.read(); 
  }

  // Bluetooth Commands
  if (SerialBT.available()) {
    char btCmd = SerialBT.read();
    if (btCmd == 'S') sendFullStatus(); 
    else if (btCmd == 'A') processCommand(CMD_LIGHT_ON, "App");
    else if (btCmd == 'a') processCommand(CMD_LIGHT_OFF, "App");
    else if (btCmd == 'B') processCommand(CMD_FAN_ON, "App");
    else if (btCmd == 'b') processCommand(CMD_FAN_OFF, "App");
    else if (btCmd == 'C') processCommand(CMD_MODE_AUTO, "App");
    else if (btCmd == 'c') processCommand(CMD_MODE_MAN, "App");
    else if (btCmd == 'D') processCommand(CMD_CURTAIN_OPEN, "App");
    else if (btCmd == 'd') processCommand(CMD_CURTAIN_CLOSE, "App");
  }

  // Auto Logic
  if (isAutoMode) {
    if (temp >= TEMP_THRESHOLD_HIGH) {
      if (!fanState && !autoFanOverrideOFF) {
        digitalWrite(RELAY_FAN, LOW); 
        fanState = true;
        preferences.putBool("fan_state", true); // Save auto-change
        digitalWrite(BUZZER_PIN, HIGH);
        delay(1500); 
        digitalWrite(BUZZER_PIN, LOW);
        sendFullStatus(); 
        updateDisplay();
      }
    } 
    else if (temp < TEMP_THRESHOLD_LOW) {
      if (fanState && !autoFanOverrideON) {
        digitalWrite(RELAY_FAN, HIGH); 
        fanState = false;
        preferences.putBool("fan_state", false); // Save auto-change
        sendFullStatus(); 
        updateDisplay();
      }
      autoFanOverrideOFF = false; 
    }
  }

  // Timer-Based Curtain Stop & Resume Logic
  if (curtainStatus == C_OPENING || curtainStatus == C_CLOSING) {
    uint32_t currentElapsed = curtainElapsed + (currentMillis - curtainMoveTimer);
    
    if (currentElapsed >= CURTAIN_MOVE_TIME) {
      digitalWrite(RELAY_CURTAIN_OPEN, HIGH);
      digitalWrite(RELAY_CURTAIN_CLOSE, HIGH);
      
      if (curtainStatus == C_OPENING) curtainStatus = C_OPEN;
      else if (curtainStatus == C_CLOSING) curtainStatus = C_CLOSED;
      
      // Save final state and reset elapsed time to 0
      preferences.putInt("curtain_state", curtainStatus);
      preferences.putUInt("c_elapsed", 0);
      curtainElapsed = 0; 
      
      sendFullStatus();
      updateDisplay();
    } 
    // Save progress to memory every 200ms in case of sudden blackout
    else if (currentMillis - lastFlashUpdate >= 200) {
      preferences.putUInt("c_elapsed", currentElapsed);
      lastFlashUpdate = currentMillis;
    }
  }

  // Periodic Status Update
  if (currentMillis - lastBTUpdate > 2000) {
    if (SerialBT.hasClient()) sendFullStatus();
    lastBTUpdate = currentMillis;
  }
}

// ================= HELPERS =================
void sendFullStatus() {
  String statusMsg = "STATUS|";
  statusMsg += String(temp, 1) + "|";
  statusMsg += (lightState ? "1" : "0") + String("|");
  statusMsg += (fanState ? "1" : "0") + String("|");
  statusMsg += (isAutoMode ? "1" : "0") + String("|");
  
  bool isConsideredOpen = (curtainStatus == C_OPEN || curtainStatus == C_OPENING);
  statusMsg += (isConsideredOpen ? "1" : "0"); 
  SerialBT.println(statusMsg);
}

void processCommand(byte cmd, String source) {
  switch (cmd) {
    case CMD_LIGHT_ON:   
      digitalWrite(RELAY_LIGHT, LOW); 
      lightState = true; 
      preferences.putBool("light_state", true);
      break;
    case CMD_LIGHT_OFF:  
      digitalWrite(RELAY_LIGHT, HIGH); 
      lightState = false; 
      preferences.putBool("light_state", false);
      break;
    case CMD_FAN_ON:     
      digitalWrite(RELAY_FAN, LOW); 
      fanState = true; 
      preferences.putBool("fan_state", true);
      autoFanOverrideON = true; 
      autoFanOverrideOFF = false; 
      break;
    case CMD_FAN_OFF:    
      digitalWrite(RELAY_FAN, HIGH); 
      fanState = false;
      preferences.putBool("fan_state", false);
      autoFanOverrideON = false;
      if (isAutoMode && temp >= TEMP_THRESHOLD_HIGH) autoFanOverrideOFF = true; 
      break;
    case CMD_MODE_AUTO:  
      isAutoMode = true; 
      preferences.putBool("auto_mode", true);
      autoFanOverrideOFF = false; 
      autoFanOverrideON = false; 
      break;
    case CMD_MODE_MAN:   
      isAutoMode = false; 
      preferences.putBool("auto_mode", false);
      digitalWrite(RELAY_FAN, HIGH); 
      fanState = false; 
      preferences.putBool("fan_state", false);
      break;
      
    case CMD_CURTAIN_OPEN:
      if (curtainStatus == C_OPEN || curtainStatus == C_OPENING || curtainStatus == C_CLOSING) return;
      digitalWrite(RELAY_CURTAIN_CLOSE, HIGH);
      digitalWrite(RELAY_CURTAIN_OPEN, LOW);
      curtainStatus = C_OPENING; 
      curtainMoveTimer = millis();
      curtainElapsed = 0;
      preferences.putInt("curtain_state", curtainStatus);
      preferences.putUInt("c_elapsed", 0);
      break;
      
    case CMD_CURTAIN_CLOSE:
      if (curtainStatus == C_CLOSED || curtainStatus == C_CLOSING || curtainStatus == C_OPENING) return;
      digitalWrite(RELAY_CURTAIN_OPEN, HIGH);
      digitalWrite(RELAY_CURTAIN_CLOSE, LOW);
      curtainStatus = C_CLOSING; 
      curtainMoveTimer = millis();
      curtainElapsed = 0;
      preferences.putInt("curtain_state", curtainStatus);
      preferences.putUInt("c_elapsed", 0);
      break;
  }
  sendFullStatus();
  updateDisplay();
}

// ================= MAIN UI =================
void updateDisplay() {
  display.clearBuffer();

  display.setDrawColor(1);
  display.drawBox(0, 0, 128, 14); 
  display.setDrawColor(0);        

  // --- MODE TEXT (Center) ---
  display.setFont(u8g2_font_6x12_tr);
  String modeText = isAutoMode ? "AUTO MODE" : "MANUAL MODE";
  int modeW = display.getStrWidth(modeText.c_str());
  int modeX = (128 - modeW) / 2;
  if (modeX < 26) modeX = 26; 
  display.drawStr(modeX, 2, modeText.c_str());

  // --- LOGO (Top Left) ---
  display.setFont(u8g2_font_4x6_tr);
  display.drawStr(3, 4, "NEXUS");

  // --- BLUETOOTH INDICATOR (Top Right) ---
  const char* btText = SerialBT.hasClient() ? "CONNECTED" : "NOT CONNECTED";
  int btX = 126 - display.getStrWidth(btText); 
  display.drawStr(btX, 4, btText);

  display.setDrawColor(1);

  // --- TEMPERATURE SECTION ---
  char tempStr[8];
  dtostrf(temp, 4, 1, tempStr);
  
  display.setFont(u8g2_font_logisoso24_tf); 
  int tempW = display.getStrWidth(tempStr);
  
  display.setFont(u8g2_font_helvB10_tf);
  int symbolW = display.getStrWidth("\xb0""C"); 
  
  int totalTempW = tempW + 2 + symbolW; 
  int startX = (128 - totalTempW) / 2;

  display.setFont(u8g2_font_logisoso24_tf);
  display.drawStr(startX, 18, tempStr);
  display.setFont(u8g2_font_helvB10_tf);
  display.drawStr(startX + tempW + 2, 18, "\xb0""C");

  display.drawHLine(0, 44, 128); 
  display.drawVLine(42, 44, 20); 
  display.drawVLine(85, 44, 20); 

  display.setFont(u8g2_font_5x8_tr); 

  // Column 1: LIGHT
  const char* lightLabel = "LIGHT";
  const char* lightVal = lightState ? "ON" : "OFF";
  display.drawStr(21 - (display.getStrWidth(lightLabel)/2), 47, lightLabel);
  display.drawStr(21 - (display.getStrWidth(lightVal)/2), 56, lightVal);

  // Column 2: FAN
  const char* fanLabel = "FAN";
  const char* fanVal = fanState ? "ON" : "OFF";
  display.drawStr(64 - (display.getStrWidth(fanLabel)/2), 47, fanLabel);
  display.drawStr(64 - (display.getStrWidth(fanVal)/2), 56, fanVal);

  // Column 3: CURTAIN
  const char* curtainLabel = "CURTAIN";
  const char* curtainVal = "";
  
  switch(curtainStatus) {
    case C_CLOSED:  curtainVal = "CLOSED";  break;
    case C_OPEN:    curtainVal = "OPEN";    break;
    case C_OPENING: curtainVal = "OPENING"; break;
    case C_CLOSING: curtainVal = "CLOSING"; break;
  }
  
  display.drawStr(106 - (display.getStrWidth(curtainLabel)/2), 47, curtainLabel);
  display.drawStr(106 - (display.getStrWidth(curtainVal)/2), 56, curtainVal);

  display.sendBuffer();
}
