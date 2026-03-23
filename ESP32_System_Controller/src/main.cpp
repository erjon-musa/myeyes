#include <Arduino.h>
#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>
#include <driver/i2s.h>
#include <math.h>

// ----- WiFi SoftAP Settings -----
const char* WIFI_SSID = "MyEyes";
const char* WIFI_PASS = "myeyes123";

// ----- Camera Pin Mapping (Freenove ESP32-S3 WROOM) -----
#define CAM_PIN_PWDN    -1
#define CAM_PIN_RESET   -1
#define CAM_PIN_XCLK    15
#define CAM_PIN_SIOD    4
#define CAM_PIN_SIOC    5
#define CAM_PIN_D7      16
#define CAM_PIN_D6      17
#define CAM_PIN_D5      18
#define CAM_PIN_D4      12
#define CAM_PIN_D3      10
#define CAM_PIN_D2      8
#define CAM_PIN_D1      9
#define CAM_PIN_D0      11
#define CAM_PIN_VSYNC   6
#define CAM_PIN_HREF    7
#define CAM_PIN_PCLK    13

// ----- Audio Pin Mapping -----
#define I2S_BCLK  42
#define I2S_LRC   41
#define I2S_DOUT  1

#define SAMPLE_RATE 44100
#define I2S_PORT    I2S_NUM_0

WebServer server(80);
WiFiServer audioServer(81);  // Separate port for receiving audio
bool cameraReady = false;

// ============================================================
//  Audio
// ============================================================
void setupAudio() {
  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 16,
    .dma_buf_len = 256,
    .use_apll = false,
    .tx_desc_auto_clear = true,
  };
  i2s_pin_config_t pins = {
    .bck_io_num = I2S_BCLK,
    .ws_io_num = I2S_LRC,
    .data_out_num = I2S_DOUT,
    .data_in_num = I2S_PIN_NO_CHANGE,
  };
  i2s_driver_install(I2S_PORT, &cfg, 0, NULL);
  i2s_set_pin(I2S_PORT, &pins);
  Serial.println("[OK] Audio initialized");
}

void beep(int freq, int ms, int volume) {
  int samples = (SAMPLE_RATE * ms) / 1000;
  for (int i = 0; i < samples; i++) {
    float t = (float)i / SAMPLE_RATE;
    int16_t val = (int16_t)(volume * sin(2.0 * M_PI * freq * t));
    int16_t buf[2] = {val, val};
    size_t bw;
    i2s_write(I2S_PORT, buf, sizeof(buf), &bw, portMAX_DELAY);
  }
}

void silence(int ms) {
  int samples = (SAMPLE_RATE * ms) / 1000;
  int16_t buf[2] = {0, 0};
  size_t bw;
  for (int i = 0; i < samples; i++) {
    i2s_write(I2S_PORT, buf, sizeof(buf), &bw, portMAX_DELAY);
  }
}

// Startup chime — plays on boot to confirm audio is working
void playStartupChime() {
  beep(523, 150, 8000);  // C5
  silence(50);
  beep(659, 150, 8000);  // E5
  silence(50);
  beep(784, 150, 8000);  // G5
  silence(50);
  beep(1047, 300, 8000); // C6 (hold)
  silence(200);
  beep(784, 150, 8000);  // G5
  silence(50);
  beep(1047, 400, 8000); // C6 (hold longer)
  silence(500);
}

// ============================================================
//  Audio Receive (from Android app)
// ============================================================
// FreeRTOS task — runs on Core 0 so it works alongside the
// camera stream which blocks the main loop on Core 1.
//
// Accepts WAV files via HTTP POST. Parses the 44-byte WAV header
// to extract sample rate and channel count, reconfigures I2S to
// match, streams the PCM payload to the speakers, then restores
// the original I2S settings.

// Helper: read exactly n bytes from client (blocking)
static bool readExact(WiFiClient &client, uint8_t *buf, int n) {
  int got = 0;
  while (got < n && client.connected()) {
    int avail = client.available();
    if (avail > 0) {
      int r = client.read(buf + got, min(avail, n - got));
      if (r > 0) got += r;
    } else {
      vTaskDelay(1);
    }
  }
  return got == n;
}

void audioTask(void* pvParameters) {
  for (;;) {
    WiFiClient client = audioServer.available();
    if (client) {
      Serial.println("[..] Audio client connected");

      // Parse HTTP headers
      int contentLength = 0;
      while (client.connected()) {
        String line = client.readStringUntil('\n');
        line.trim();
        if (line.length() == 0) break;
        if (line.startsWith("Content-Length:")) {
          contentLength = line.substring(15).toInt();
        }
      }

      if (contentLength > 44) {
        // ------- Read WAV header (44 bytes) -------
        uint8_t hdr[44];
        if (readExact(client, hdr, 44)) {
          // WAV header layout (little-endian):
          //   bytes 22-23 : numChannels (1=mono, 2=stereo)
          //   bytes 24-27 : sampleRate
          uint16_t numChannels = hdr[22] | (hdr[23] << 8);
          uint32_t wavSampleRate = hdr[24] | (hdr[25] << 8) |
                                   (hdr[26] << 16) | (hdr[27] << 24);

          Serial.printf("[..] WAV: %d Hz, %d ch\n", wavSampleRate, numChannels);

          // Reconfigure I2S to match the incoming audio
          i2s_channel_fmt_t chFmt = (numChannels == 1)
              ? I2S_CHANNEL_FMT_ONLY_LEFT
              : I2S_CHANNEL_FMT_RIGHT_LEFT;
          i2s_set_clk(I2S_PORT, wavSampleRate,
                      I2S_BITS_PER_SAMPLE_16BIT, 
                      (numChannels == 1) ? I2S_CHANNEL_MONO : I2S_CHANNEL_STEREO);

          // ------- Stream PCM payload to I2S -------
          int pcmBytes = contentLength - 44;
          uint8_t buf[512];
          int totalRead = 0;
          while (totalRead < pcmBytes && client.connected()) {
            int avail = client.available();
            if (avail > 0) {
              int toRead = min(avail, (int)sizeof(buf));
              toRead = min(toRead, pcmBytes - totalRead);
              int n = client.read(buf, toRead);
              if (n > 0) {
                size_t bw;
                i2s_write(I2S_PORT, buf, n, &bw, portMAX_DELAY);
                totalRead += n;
              }
            } else {
              vTaskDelay(1);
            }
          }

          // Restore original I2S settings (44100 Hz stereo)
          i2s_set_clk(I2S_PORT, SAMPLE_RATE,
                      I2S_BITS_PER_SAMPLE_16BIT, I2S_CHANNEL_STEREO);

          Serial.printf("[OK] Audio played: %d bytes (%d Hz, %dch)\n",
                        totalRead, wavSampleRate, numChannels);
        } else {
          Serial.println("[FAIL] Could not read WAV header");
        }
      } else if (contentLength > 0) {
        // Fallback: raw PCM (no WAV header), play as-is
        uint8_t buf[512];
        int totalRead = 0;
        while (totalRead < contentLength && client.connected()) {
          int avail = client.available();
          if (avail > 0) {
            int toRead = min(avail, (int)sizeof(buf));
            toRead = min(toRead, contentLength - totalRead);
            int n = client.read(buf, toRead);
            if (n > 0) {
              size_t bw;
              i2s_write(I2S_PORT, buf, n, &bw, portMAX_DELAY);
              totalRead += n;
            }
          } else {
            vTaskDelay(1);
          }
        }
        Serial.printf("[OK] Raw audio played: %d bytes\n", totalRead);
      } else {
        Serial.println("[WARN] Audio request with no Content-Length");
      }

      // Send HTTP response
      client.println("HTTP/1.1 200 OK");
      client.println("Access-Control-Allow-Origin: *");
      client.println("Content-Length: 2");
      client.println();
      client.print("OK");
      client.stop();
    }
    vTaskDelay(10 / portTICK_PERIOD_MS);
  }
}

// ============================================================
//  Camera
// ============================================================
bool setupCamera() {
  Serial.println("[..] Initializing camera...");
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = CAM_PIN_D0;
  config.pin_d1 = CAM_PIN_D1;
  config.pin_d2 = CAM_PIN_D2;
  config.pin_d3 = CAM_PIN_D3;
  config.pin_d4 = CAM_PIN_D4;
  config.pin_d5 = CAM_PIN_D5;
  config.pin_d6 = CAM_PIN_D6;
  config.pin_d7 = CAM_PIN_D7;
  config.pin_xclk = CAM_PIN_XCLK;
  config.pin_pclk = CAM_PIN_PCLK;
  config.pin_vsync = CAM_PIN_VSYNC;
  config.pin_href = CAM_PIN_HREF;
  config.pin_sccb_sda = CAM_PIN_SIOD;
  config.pin_sccb_scl = CAM_PIN_SIOC;
  config.pin_pwdn = CAM_PIN_PWDN;
  config.pin_reset = CAM_PIN_RESET;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_SXGA;   // 1280x1024
  config.jpeg_quality = 10;
  config.fb_count = 2;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("[FAIL] Camera init error: 0x%x\n", err);
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    s->set_brightness(s, 1);
    s->set_contrast(s, 1);
    s->set_saturation(s, 0);
  }

  camera_fb_t *fb = esp_camera_fb_get();
  if (fb) {
    Serial.printf("[OK] Camera ready! Test frame: %d bytes (%dx%d)\n",
                  fb->len, fb->width, fb->height);
    esp_camera_fb_return(fb);
    return true;
  }
  Serial.println("[FAIL] Camera test capture failed!");
  return false;
}

// ============================================================
//  WiFi + Web Server
// ============================================================
void setupWiFi() {
  Serial.println("[..] Starting WiFi AP...");
  WiFi.mode(WIFI_AP);
  WiFi.softAP(WIFI_SSID, WIFI_PASS);
  delay(500);
  IPAddress ip = WiFi.softAPIP();
  Serial.printf("[OK] WiFi AP ready! SSID: %s  Password: %s\n", WIFI_SSID, WIFI_PASS);
  Serial.printf("[OK] Open browser: http://%s\n", ip.toString().c_str());
}

void handleRoot() {
  String html;
  if (cameraReady) {
    html = R"rawhtml(
<!DOCTYPE html><html><head><title>MyEyes</title>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<style>body{background:#111;color:#fff;font-family:Arial;text-align:center;margin:0;padding:20px}
h1{color:#4fc3f7} img{max-width:100%;border:2px solid #333;border-radius:8px}
.info{color:#888;margin:10px} a{color:#4fc3f7}</style></head><body>
<h1>MyEyes Camera Feed</h1>
<img src="/stream" />
<p class="info">1280x1024 JPEG | Freenove ESP32-S3</p>
<p class="info"><a href="/frame">Single JPEG snapshot</a></p>
</body></html>)rawhtml";
  } else {
    html = R"rawhtml(
<!DOCTYPE html><html><head><title>MyEyes</title>
<style>body{background:#111;color:#fff;font-family:Arial;text-align:center;padding:50px}
h1{color:#f44} .info{color:#888}</style></head><body>
<h1>Camera Not Available</h1>
<p class="info">Camera failed to initialize. WiFi and audio are working.</p>
</body></html>)rawhtml";
  }
  server.send(200, "text/html", html);
}

void handleFrame() {
  if (!cameraReady) { server.send(503, "text/plain", "Camera not available"); return; }
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) { server.send(500, "text/plain", "Capture failed"); return; }
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send_P(200, "image/jpeg", (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

void handleStream() {
  if (!cameraReady) { server.send(503, "text/plain", "Camera not available"); return; }
  WiFiClient client = server.client();
  client.setNoDelay(true);

  client.println("HTTP/1.1 200 OK");
  client.println("Content-Type: multipart/x-mixed-replace; boundary=frame");
  client.println("Access-Control-Allow-Origin: *");
  client.println();

  while (client.connected()) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) { delay(100); continue; }

    client.printf("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", fb->len);
    client.write(fb->buf, fb->len);
    client.println();
    
    esp_camera_fb_return(fb);
    
    // Add a tiny delay to give the WiFi stack time to process the buffer
    delay(5);
  }
}

// ============================================================
//  Main
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("===================================");
  Serial.println("  MyEyes — Booting");
  Serial.println("===================================");

  // 1. Audio
  setupAudio();

  // 2. Startup chime
  Serial.println("[..] Playing startup chime...");
  playStartupChime();
  Serial.println("[OK] Audio ready");

  // 3. WiFi (before camera so it's always available)
  setupWiFi();

  // 4. Camera
  cameraReady = setupCamera();

  // 5. Web server
  server.on("/", handleRoot);
  server.on("/frame", handleFrame);
  server.on("/stream", handleStream);
  server.begin();

  // 6. Audio receive server + task on Core 0
  audioServer.begin();
  xTaskCreatePinnedToCore(audioTask, "audioTask", 4096, NULL, 1, NULL, 0);
  Serial.println("[OK] Audio receive server on port 81");

  Serial.println("===================================");
  Serial.println("  All systems initialized!");
  Serial.println("  WiFi: MyEyes / myeyes123");
  Serial.println("  Stream : http://192.168.4.1");
  Serial.println("  Audio  : http://192.168.4.1:81");
  Serial.println("===================================");
}

void loop() {
  server.handleClient();
  delay(1);
}