# Smart Cane — Complete Wiring Reference

Audio output decision: no physical speaker. AirPods (via the phone)
are the only audio output. The DFPlayer Mini stays wired and running
anyway — see the note at the bottom of this file for why.

## ARDUINO UNO

**Obstacle sensors (3x HC-SR04)** — each also needs VCC->5V, GND->GND

| Sensor | Trig pin | Echo pin |
|---|---|---|
| Left | 2 | 3 |
| Center | 4 | 5 |
| Right | 6 | 7 |

**Ground/stair sensors (2x HC-SR04)**

| Sensor | Trig pin | Echo pin |
|---|---|---|
| Ground (faces down) | 8 | 9 |
| Stair-up | 12 | 13 |

**DFPlayer Mini** — kept for timing only, no speaker attached

| DFPlayer pin | Connects to |
|---|---|
| TX | Uno pin 10 |
| RX | Uno pin 11 |
| BUSY | Uno pin A0 |
| VCC | 5V |
| GND | GND |
| SPK_1 / SPK_2 | not connected -- no speaker |

**SOS button:** one leg -> Uno pin A1, other leg -> GND

**MPU6050 (tilt sensor)**

| MPU6050 pin | Connects to |
|---|---|
| SDA | Uno pin A4 |
| SCL | Uno pin A5 |
| VCC | 5V |
| GND | GND |

**Link to ESP32**

| Uno pin | Connects to |
|---|---|
| A2 (RX) | ESP32 pin 17 -- direct wire |
| A3 (TX) | ESP32 pin 16 -- THROUGH A VOLTAGE DIVIDER, not direct |
| GND | ESP32 GND -- must be connected |

## ESP32

**Link to Uno**

| ESP32 pin | Connects to |
|---|---|
| GPIO16 (RX2) | Uno pin A3 -- through the same voltage divider above |
| GPIO17 (TX2) | Uno pin A2 -- direct wire |

**Power**

| ESP32 pin | Connects to |
|---|---|
| VIN | Uno 5V pin (not 3V3 -- VIN has the onboard regulator) |
| GND | shared GND (already required above) |

Risk: ESP32 draws sharp current spikes (300-500mA) when Bluetooth
transmits. If the Uno itself is only powered by a laptop USB port,
there may not be enough headroom for both boards, causing random
ESP32 resets / Bluetooth drops that look like software bugs. Fine
for USB-tethered testing if it stays stable; for the actual
battery-powered cane, whatever powers the Uno needs enough current
headroom for both boards combined. This is separate from SIM800L,
which still needs its own dedicated supply regardless (see below).

**Neo-6M GPS module**

| GPS pin | Connects to |
|---|---|
| TX | ESP32 GPIO25 |
| RX | ESP32 GPIO26 |
| VCC | 5V |
| GND | GND |

**SIM800L module**

| SIM800L pin | Connects to |
|---|---|
| TX | ESP32 GPIO27 |
| RX | ESP32 GPIO14 |
| VCC | its own separate power supply (~4V), NOT the ESP32's power pin |
| GND | same GND as ESP32 |

**Battery level wire:** battery positive -> through a divider (two equal
resistors, e.g. 100k + 100k) -> ESP32 GPIO34

**Bluetooth:** nothing to wire -- built into the ESP32 chip.

## The three things that can break something if wrong

1. Uno A3 -> ESP32 pin 16 needs the voltage divider. Direct
   connection can damage the ESP32.
2. SIM800L needs its own power supply, not power from the ESP32 --
   it can't supply enough current when sending a text.
3. Don't remove the DFPlayer Mini even with no speaker attached --
   the Uno's alert pacing depends on reading its BUSY pin
   (digitalRead(busyPin)) to know when one alert clip has finished
   before starting the next. Speaker is optional; the module itself
   is not.

## Known consequence of no speaker

If the AirPods disconnect or the phone dies mid-walk, there is
currently no fallback audio path -- the user gets no warning at
all until reconnected. Worth a team decision on whether that's
acceptable for a safety device before the defense.
