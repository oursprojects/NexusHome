# NexusHome - Smart Home Automation

NexusHome is a modern Android application designed for intuitive smart home control and monitoring. It provides a seamless interface to manage household devices such as lights and fans, while also providing real-time temperature tracking via Bluetooth connectivity.

## 🚀 Key Features

- **Bluetooth Connectivity**: Effortlessly connect to and control "NexusHome" smart devices over Bluetooth.
- **Real-time Monitoring**: Monitor ambient temperature with dynamic visual indicators and status summaries.
- **Smart Controls**:
  - **Light Control**: Toggle home lighting on and off.
  - **Fan Control**: Manage fan operation with a simple switch.
  - **Operation Modes**: Switch between **Automatic** (sensor-driven) and **Manual** control modes.
- **Command History**: A built-in log to track device interactions and system activities.
- **Glassmorphism UI**: A premium, borderless design with smooth gradients and interactive micro-animations.

## 🛠️ Tech Stack

- **Language**: Kotlin
- **UI Framework**: Modern Android XML with ViewBinding and Material Design 3.
- **Architecture**: MVVM (Model-View-ViewModel) with StateFlows.
- **Database**: Room Persistence Library for local command logging.
- **Networking**: Android Bluetooth Serial/GATT for device communication.

## 👥 The Team

- **Leader**: Mike Ryno Santiago
- **Members**:
  - Karylle Jamie Ladera Marimon
  - Jestoni Flores
  - Deejay Angelo de La Cruz

## 📋 System Requirements

- **Android Version**: Android 8.0 (Oreo) or higher.
- **Bluetooth**: Device must support Bluetooth 4.0+.
- **Permissions**:
  - Bluetooth Scan & Connect
  - Fine Location (Required for Bluetooth scanning on Android)

## 🔧 Setup & Installation

1.  Clone the repository:
    ```bash
    git clone https://github.com/oursprojects/NexusHome.git
    ```
2.  Open the project in **Android Studio**.
3.  Sync the project with Gradle files.
4.  Build and Run on an Android device or emulator (physical device recommended for Bluetooth testing).

---
*Created as part of a Capstone Project for NexusHome.*
