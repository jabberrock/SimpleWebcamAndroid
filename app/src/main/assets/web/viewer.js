(function () {
  'use strict';

  /** @type {RTCPeerConnection | null} */
  let activePc = null;

  function closePeerConnection() {
    const pc = activePc;
    activePc = null;
    if (pc) {
      try {
        pc.close();
      } catch (e) {
        /* ignore */
      }
    }
    const video = document.getElementById('video');
    if (video) {
      video.srcObject = null;
    }
  }

  window.addEventListener('pagehide', closePeerConnection, false);

  let reconnectTimeout = null;

  function scheduleRetry() {
    clearTimeout(reconnectTimeout);
    reconnectTimeout = setTimeout(connect, 1000);
  }

  function waitForIceGatheringComplete(pc) {
    if (pc.iceGatheringState === 'complete') {
      return Promise.resolve();
    }
    return new Promise((resolve) => {
      const check = () => {
        if (pc.iceGatheringState === 'complete') {
          pc.removeEventListener('icegatheringstatechange', check);
          resolve();
        }
      };
      pc.addEventListener('icegatheringstatechange', check);
      check();
    });
  }

  async function connect() {
    const video = document.getElementById('video');
    if (!video) {
      console.error('No #video element');
      return;
    }

    try {
      await createPeerConnection()
    } catch (e) {
      console.error(e);
      closePeerConnection();
      scheduleRetry();
    }
  }

  async function createPeerConnection() {
    const pc = new RTCPeerConnection({ iceServers: [] });
    activePc = pc;

    pc.addTransceiver('video', { direction: 'recvonly' });

    const visionDc = pc.createDataChannel('vision');
    visionDc.addEventListener('message', function (ev) {
      if (typeof ev.data === 'string') {
        console.log('[datachannel]', visionDc.label, JSON.parse(ev.data));
      }
    });
    visionDc.addEventListener('close', function() {
      closePeerConnection();
      scheduleRetry();
    });

    pc.addEventListener('track', function (ev) {
      const stream =
        ev.streams && ev.streams.length > 0 && ev.streams[0].getTracks().length > 0
          ? ev.streams[0]
          : ev.track ? new MediaStream([ev.track]) : null;
      if (!stream) {
        return;
      }
      video.srcObject = stream;
      video.play().catch(function (err) {
        console.warn('video.play failed', err);
      });
    });

    pc.addEventListener('connectionstatechange', function () {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        closePeerConnection();
        scheduleRetry();
      }
    });

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await waitForIceGatheringComplete(pc);

    const offerSdp = pc.localDescription.sdp;
    const res = await fetch('/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ offerSdp }),
    });

    if (!res.ok) {
      const errText = await res.text();
      console.error('POST /connect failed:', res.status, errText);
      throw new Error('Signaling failed: ' + res.status);
    }

    const { answerSdp } = await res.json();
    await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
  }

  connect();
})();
