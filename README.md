# Simple Webcam

Simple Webcam is... *drumroll*... a simple webcam app.

Start it on your phone, then point your web browser to
http://phone_ip_address:8080/, and you'll immediately be connected to your
phone's camera. No proprietary software needed. Low latency and high quality
video.

Useful for video calls, streaming on OBS Studio, or computer vision projects.

## WebRTC

Simple Webcam uses [WebRTC](https://webrtc.org/), a modern open-source protocol
for sending audio, video and data. WebRTC is supported by any modern browser and
has libraries for any language on any platform. WebRTC provides low latency and
high quality video compared to older protocols like RTMP.

To connect to Simple Webcam, just point your web browser to
http://phone_ip_address:8080/.

If you want to connect to Simple Webcam programmatically, you can set up a
WebRTC peer connection, gather ICE candidates ("how can I be reached"), and make
a request to Simple Webcam:
```
POST /connect
Content-Type: application/json
{
  "offerSdp": "...",
}
```
You will receive a response:
```
{
  "answerSdp": "..."
}
```
You can provide this answer to the WebRTC peer connection to complete the
handshake, and start receiving audio and video.

## mDNS

Simple Webcam is discoverable over mDNS as `_simple-webcam._tcp`.

## Computer Vision

Simple Webcam provides some data that is useful for computer vision projects.
If you create a `vision` WebRTC data channel before connecting, Simple Webcam
will periodically send the camera's intrinsic and orientation in space to the
data channel:
```
{
  "cameraIntrinsic": {
    "fx": 938.5,                    // focal length
    "fy": 938.5,
    "tx": 360,                      // principal point
    "ty": 640,
    "width": 1280,
    "height": 720,
  },
  "deviceToArbitraryZVertical": {   // quaternion rotation from phone space (X-right, Y-up, Z-out-of-screen)
    "w": ...,                       // to an arbitrary right-handed world space with Y opposing direction of gravity
    "x": ...,
    "y": ...,
    "z": ...
  }
}
```
