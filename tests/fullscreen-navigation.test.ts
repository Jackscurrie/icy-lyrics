import { describe, expect, it } from "vitest";
import { stepFullscreenView, type FullscreenView } from "../src/utils/FullscreenPresentation.ts";
import {
  bindFullscreenViewNavigation,
  ensureFullscreenViewToolbar,
} from "../src/utils/FullscreenViewNavigation.ts";

class FakeElement extends EventTarget {
  disabled = false;
  tabIndex = 0;
  textContent: string | null = null;
  readonly attributes = new Map<string, string>();
  readonly selectors = new Map<string, FakeElement>();

  querySelector<T>(selector: string): T | null {
    return (this.selectors.get(selector) as T | undefined) ?? null;
  }

  setAttribute(name: string, value: string): void {
    this.attributes.set(name, value);
  }

  getAttribute(name: string): string | null {
    return this.attributes.get(name) ?? null;
  }

  matches(): boolean {
    return false;
  }
}

class FakePage extends FakeElement {
  insertions = 0;

  insertAdjacentHTML(): void {
    this.insertions += 1;
    const toolbar = new FakeElement();
    toolbar.selectors.set(".FullscreenViewEdge--previous", new FakeElement());
    toolbar.selectors.set(".FullscreenViewEdge--next", new FakeElement());
    toolbar.selectors.set(".FullscreenViewStatus", new FakeElement());
    this.selectors.set(".FullscreenViewToolbar", toolbar);
  }
}

class FakeKeyboardEvent extends Event {
  readonly key: string;
  readonly altKey = false;
  readonly ctrlKey = false;
  readonly metaKey = false;

  constructor(key: string) {
    super("keydown", { cancelable: true });
    this.key = key;
  }
}

const asPage = (page: FakePage) => page as unknown as HTMLElement;
const asKeyboardTarget = (target: FakeElement) => target as unknown as Document;

describe("fullscreen navigation lifecycle", () => {
  it("keeps one page-owned toolbar across cleanup and rebinds once on reopen", () => {
    const page = new FakePage();
    const keyboardTarget = new FakeElement();
    let current: FullscreenView = "mixed";
    let stepCount = 0;
    let active = true;
    let handle: ReturnType<typeof bindFullscreenViewNavigation>;

    const bind = (controller: AbortController) => {
      handle = bindFullscreenViewNavigation(
        asPage(page),
        asKeyboardTarget(keyboardTarget),
        controller.signal,
        {
          getCurrentView: () => current,
          isActive: () => active,
          onStep: (direction) => {
            stepCount += 1;
            current = stepFullscreenView(current, direction);
            handle.update(current);
          },
        }
      );
      return handle;
    };

    const firstController = new AbortController();
    const firstHandle = bind(firstController);
    const toolbar = firstHandle.toolbar as unknown as FakeElement;
    const previous = toolbar.querySelector<FakeElement>(".FullscreenViewEdge--previous")!;
    const next = toolbar.querySelector<FakeElement>(".FullscreenViewEdge--next")!;

    // Media/NowBar cleanup owns a different controller and must not affect the
    // stable navigation session or remove its page-owned node.
    const unrelatedMediaController = new AbortController();
    unrelatedMediaController.abort();
    expect(ensureFullscreenViewToolbar(asPage(page))).toBe(firstHandle.toolbar);
    next.dispatchEvent(new Event("click"));
    expect(current).toBe("lyrics");

    // The right endpoint is bounded and does not wrap or invoke SetView again.
    next.dispatchEvent(new Event("click"));
    expect(current).toBe("lyrics");
    expect(stepCount).toBe(1);

    firstController.abort();
    previous.dispatchEvent(new Event("click"));
    expect(current).toBe("lyrics");

    const secondController = new AbortController();
    const secondHandle = bind(secondController);
    expect(secondHandle.toolbar).toBe(firstHandle.toolbar);
    previous.dispatchEvent(new Event("click"));
    expect(current).toBe("mixed");
    expect(stepCount).toBe(2);
    expect(page.insertions).toBe(1);

    active = false;
    previous.dispatchEvent(new Event("click"));
    expect(current).toBe("mixed");
  });

  it("updates endpoint accessibility and handles arrows from the document", () => {
    const page = new FakePage();
    const keyboardTarget = new FakeElement();
    const controller = new AbortController();
    let current: FullscreenView = "artwork-only";
    let handle: ReturnType<typeof bindFullscreenViewNavigation>;

    handle = bindFullscreenViewNavigation(
      asPage(page),
      asKeyboardTarget(keyboardTarget),
      controller.signal,
      {
        getCurrentView: () => current,
        isActive: () => true,
        onStep: (direction) => {
          current = stepFullscreenView(current, direction);
          handle.update(current);
        },
      }
    );

    const toolbar = handle.toolbar as unknown as FakeElement;
    const previous = toolbar.querySelector<FakeElement>(".FullscreenViewEdge--previous")!;
    const next = toolbar.querySelector<FakeElement>(".FullscreenViewEdge--next")!;
    const status = toolbar.querySelector<FakeElement>(".FullscreenViewStatus")!;

    expect(previous.disabled).toBe(true);
    expect(previous.tabIndex).toBe(-1);
    expect(next.disabled).toBe(false);
    expect(status.textContent).toBe("Album art only");

    const right = new FakeKeyboardEvent("ArrowRight");
    keyboardTarget.dispatchEvent(right);
    expect(right.defaultPrevented).toBe(true);
    expect(current).toBe("artwork-titles");

    current = "lyrics";
    handle.update(current);
    expect(next.disabled).toBe(true);
    expect(next.tabIndex).toBe(-1);
    expect(next.getAttribute("aria-label")).toBe("No next fullscreen view");
    expect(previous.getAttribute("aria-label")).toBe("Show Album art, titles and lyrics");
  });
});
