# Simple Webcam

Simple Webcam is... *drumroll*... a simple webcam app.

Start it on your phone, then point your web browser to
http://phone_ip_address:8080/, and you'll immediately see what your phone's
camera sees. No proprietary software needed. Low latency and high quality video.

Useful for video calls, as a webcam for streaming on OBS Studio, or as a webcam
for computer vision projects.

(Simple Webcam can only be accessed from your local network.)

## WebRTC

Simple Webcam uses [WebRTC](https://webrtc.org/), a modern open-source protocol
for sending audio, video and data. WebRTC is supported by any modern browser and
has libraries for any language on any platform. WebRTC provides low latency and
high quality video compared to older protocols like RTMP.

To connect to Simple Webcam, just point your web browser to
http://phone_ip_address:8080/. You will be able to select the camera,
resolution, frame rate, and more.

If you want to connect to Simple Webcam programmatically, you can set up a
WebRTC peer connection, gather ICE candidates ("how can I be reached"), and make
a request to Simple Webcam:
```
POST /connect
Content-Type: application/json
{
  "offer_sdp": "...",
  "camera": "front",
  "orientation": "portrait",
  "image_width": 720,
  "image_height": 1280,
  "fps": 30,
  ...
}
```
You will receive a response:
```
{
  "answer_sdp": "..."
}
```
You can provide this answer to the WebRTC peer connection to complete the
handshake, and start receiving audio and video.

## Progressive Web App (PWA)

The app at http://phone_ip_address:8080/ can be installed as a Progressive Web
App, which hides all the browser chrome, and only shows the video. This is
useful for sharing it on a video call.

Traditional webcams install software and drivers into Windows. The Simple Webcam
PWA offers a simpler one-click solution.

## mDNS

Simple Webcam is discoverable over mDNS as `_simple_webcam._tcp`.

## Computer Vision

Simple Webcam provides some data that is useful for computer vision projects.
It will periodically send the camera's intrinsic and orientation in space
to the `vision` WebRTC data channel:
```
{
  "timestamp": 155324234,       // nsec since connection
  "camera_intrinsic": {
    "fx": 938.5,                // focal length
    "fy": 938.5,
    "tx": 360,                  // principal point
    "ty": 640
  },
  "camera_to_world_y_up": {     // quaternion rotation from camera space (X-right, Y-down, Z-forward)
    "w": ...,                   // to an arbitrary right-handed world space with Y opposing direction of gravity
    "x": ...,
    "y": ...,
    "z": ...
  }
}
```

## Access Control

Simple Webcam provides rudimentary access control. The first time you connect to
Simple Webcam, you will need to approve or deny the connection from the phone.
