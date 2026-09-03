import ReactDOM from "react-dom/client";
import { flushSync } from "react-dom";
import { PopupModal } from "../components/Modal.ts";
import LyricsDBPanel from "../components/ReactComponents/LyricsManager/index.tsx";
import UploadTTMLModal from "../components/ReactComponents/LyricsManager/components/UploadTTMLModal.tsx";
import Session from "../components/Global/Session.ts";
import { PrepareLyricCreatorFullscreen } from "./openLyricCreator.tsx";

export function OpenLyricsDBPanel() {
  const container = document.createElement("div");
  const root = ReactDOM.createRoot(container);

  flushSync(() => {
    root.render(<LyricsDBPanel onUploadClick={_openUpload} onCreatorClick={_openCreator} />);
  });

  PopupModal.display({
    title: "Local Lyrics DB",
    content: container,
    isLarge: true,
    onClose: () => root.unmount(),
  });
}

function _openUpload() {
  const container = document.createElement("div");
  const root = ReactDOM.createRoot(container);

  flushSync(() => {
    root.render(<UploadTTMLModal onBack={_openDB} onDone={_openDB} />);
  });

  PopupModal.transition({
    content: container,
    onClose: () => root.unmount(),
    closeHandler: _openDB,
  });
}

function _openCreator() {
  PrepareLyricCreatorFullscreen();
  PopupModal.hide();
  Session.Navigate({ pathname: "/IcyLyrics/creator" });
}

function _openDB() {
  const container = document.createElement("div");
  const root = ReactDOM.createRoot(container);

  flushSync(() => {
    root.render(<LyricsDBPanel onUploadClick={_openUpload} onCreatorClick={_openCreator} />);
  });

  PopupModal.transition({
    content: container,
    onClose: () => root.unmount(),
  });
}
