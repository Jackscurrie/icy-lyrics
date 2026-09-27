/** Own the listeners for one live lyric scroller. Rebinding also restores the
 * window listeners after a song/page cleanup, without retaining old scrollers. */
export class ScrollEventBindings {
  private release: (() => void) | null = null;

  bind(
    windowTarget: EventTarget,
    scrollTarget: EventTarget | null,
    onFocus: EventListener,
    onResize: EventListener,
    onUserScroll: EventListener
  ): void {
    this.dispose();
    windowTarget.addEventListener("focus", onFocus);
    windowTarget.addEventListener("resize", onResize);
    scrollTarget?.addEventListener("wheel", onUserScroll, { passive: true });
    scrollTarget?.addEventListener("touchmove", onUserScroll, { passive: true });
    this.release = () => {
      windowTarget.removeEventListener("focus", onFocus);
      windowTarget.removeEventListener("resize", onResize);
      scrollTarget?.removeEventListener("wheel", onUserScroll);
      scrollTarget?.removeEventListener("touchmove", onUserScroll);
    };
  }

  dispose(): void {
    this.release?.();
    this.release = null;
  }
}
