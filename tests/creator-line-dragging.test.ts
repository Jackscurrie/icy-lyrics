import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { attachCreatorLineDragging } from "../src/components/ReactComponents/LyricCreator/lineDragging.ts";

class Events {
  listeners = new Map<string, Set<(event: any) => void>>();
  addEventListener(type: string, fn: (event: any) => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(fn);
  }
  removeEventListener(type: string, fn: (event: any) => void) { this.listeners.get(type)?.delete(fn); }
  dispatch(type: string, values: Record<string, any> = {}) {
    const event = { target: this, button: 0, isPrimary: true, pointerId: 1, clientX: 30, clientY: 30,
      preventDefault: vi.fn(), stopImmediatePropagation: vi.fn(), ...values };
    for (const fn of this.listeners.get(type) ?? []) fn(event);
    return event;
  }
}

class Element extends Events {
  children: Element[] = [];
  parent: Element | null = null;
  scrollTop = 0;
  capture: number | null = null;
  classes = new Set<string>();
  classList = {
    add: (...values: string[]) => values.forEach((value) => this.classes.add(value)),
    remove: (...values: string[]) => values.forEach((value) => this.classes.delete(value)),
  };
  constructor(public ownerDocument: any, public kind: string, public id = "", public top = 0, public bottom = 500) { super(); }
  append(...children: Element[]) {
    for (const child of children) { child.parent = this; this.children.push(child); }
  }
  closest(selector: string): Element | null {
    if ((selector === ".il-creator-edit" && this.kind === "scroll") ||
      (selector === "article[data-creator-line-id]" && this.kind === "line") ||
      (selector === "[data-creator-line-handle]" && this.kind === "handle")) return this;
    return this.parent?.closest(selector) ?? null;
  }
  querySelectorAll() { return this.children.filter((child) => child.kind === "line"); }
  contains(target: Element): boolean { return target === this || this.children.some((child) => child.contains(target)); }
  getAttribute(name: string) { return name === "data-creator-line-id" ? this.id : null; }
  getBoundingClientRect() { return { left: 0, right: 300, top: this.top, bottom: this.bottom, height: this.bottom - this.top }; }
  setPointerCapture(id: number) { this.capture = id; }
  hasPointerCapture(id: number) { return this.capture === id; }
  releasePointerCapture() { this.capture = null; }
}

function fixture() {
  const view = Object.assign(new Events(), { setTimeout, clearTimeout,
    requestAnimationFrame: vi.fn(() => 1), cancelAnimationFrame: vi.fn() });
  const doc: any = { defaultView: view, hit: null, elementFromPoint: () => doc.hit };
  const scroll = new Element(doc, "scroll");
  const editor = new Element(doc, "editor");
  const first = new Element(doc, "line", "first", 10, 110);
  const second = new Element(doc, "line", "second", 120, 220);
  const third = new Element(doc, "line", "third", 230, 490);
  const handle = new Element(doc, "handle");
  const word = new Element(doc, "word");
  first.append(handle, word);
  editor.append(first, second, third);
  scroll.append(editor);
  const onMove = vi.fn();
  const onGrab = vi.fn();
  const dispose = attachCreatorLineDragging(editor as unknown as HTMLElement, { onMove, onGrab });
  return { view, doc, scroll, editor, first, second, third, handle, word, onMove, onGrab, dispose };
}

describe("whole-line pointer dragging", () => {
  it("moves even when a quick down/up finishes before the next animation frame", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.second;
    f.view.dispatch("pointerup", { clientY: 200 });
    expect(f.onMove).toHaveBeenCalledExactlyOnceWith("first", "second", "after");
    expect(f.handle.capture).toBeNull();
    expect(f.first.classes.size).toBe(0);
    expect(f.second.classes.size).toBe(0);
    expect(f.editor.dispatch("click", { detail: 1 }).preventDefault).toHaveBeenCalled();
    f.dispose();
  });

  it("uses the upper/lower half of a target and shows lightweight existing feedback", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.third;
    f.view.dispatch("pointermove", { clientY: 260 });
    expect(f.first.classes.has("is-line-dragging")).toBe(true);
    expect(f.third.classes.has("is-line-drop-before")).toBe(true);
    expect(f.onGrab).toHaveBeenCalledOnce();
    f.view.dispatch("pointerup", { clientY: 260 });
    expect(f.onMove).toHaveBeenCalledExactlyOnceWith("first", "third", "before");
    f.dispose();
  });

  it("retains normal clicking below the movement threshold", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.first;
    f.view.dispatch("pointermove", { clientX: 33, clientY: 32 });
    f.view.dispatch("pointerup", { clientX: 33, clientY: 32 });
    expect(f.onMove).not.toHaveBeenCalled();
    expect(f.onGrab).not.toHaveBeenCalled();
    expect(f.editor.dispatch("click", { detail: 1 }).preventDefault).not.toHaveBeenCalled();
    f.dispose();
  });

  it("does not intercept native dragging from a word rather than the line handle", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.word });
    expect(f.editor.dispatch("dragstart", { target: f.word }).preventDefault).not.toHaveBeenCalled();
    f.doc.hit = f.third;
    f.view.dispatch("pointermove", { clientY: 260 });
    f.view.dispatch("pointerup", { clientY: 260 });
    expect(f.onMove).not.toHaveBeenCalled();
    f.dispose();
  });

  it.each(["pointercancel", "Escape", "blur", "unmount"])("cancels and removes feedback on %s", (action) => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.third;
    f.view.dispatch("pointermove", { clientY: 260 });
    if (action === "Escape") {
      expect(f.view.dispatch("keydown", { key: "Escape" }).stopImmediatePropagation).toHaveBeenCalled();
    } else if (action === "unmount") f.dispose();
    else f.view.dispatch(action);
    expect(f.onMove).not.toHaveBeenCalled();
    expect(f.first.classes.size).toBe(0);
    expect(f.third.classes.size).toBe(0);
    expect(f.handle.capture).toBeNull();
    f.dispose();
    expect([...f.view.listeners.values()].every((listeners) => listeners.size === 0)).toBe(true);
  });

  it("chooses the nearest card when dropped in the gap between cards", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.editor;
    f.view.dispatch("pointerup", { clientY: 228 });
    expect(f.onMove).toHaveBeenCalledExactlyOnceWith("first", "third", "before");
    f.dispose();
  });

  it("does not move when released outside the editor or onto itself", () => {
    for (const clientY of [80, 600]) {
      const f = fixture();
      f.editor.dispatch("pointerdown", { target: f.handle });
      f.doc.hit = f.first;
      f.view.dispatch("pointerup", { clientY });
      expect(f.onMove).not.toHaveBeenCalled();
      f.dispose();
    }
  });

  it("scrolls the edit viewport near its edge and stops the frame loop on drop", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.third;
    f.view.dispatch("pointermove", { clientY: 480 });
    expect(f.scroll.scrollTop).toBeGreaterThan(0);
    expect(f.view.requestAnimationFrame).toHaveBeenCalledOnce();
    f.view.dispatch("pointerup", { clientY: 480 });
    expect(f.view.cancelAnimationFrame).toHaveBeenCalledOnce();
    f.dispose();
  });

  it("ignores another pointer during a drag", () => {
    const f = fixture();
    f.editor.dispatch("pointerdown", { target: f.handle });
    f.doc.hit = f.third;
    f.view.dispatch("pointermove", { pointerId: 2, clientY: 260 });
    f.view.dispatch("pointerup", { pointerId: 2, clientY: 260 });
    expect(f.onMove).not.toHaveBeenCalled();
    expect(f.onGrab).not.toHaveBeenCalled();
    f.dispose();
  });
});

it("uses pointer handles for lines while preserving keyboard movement and native word dragging", () => {
  const source = readFileSync(new URL("../src/components/ReactComponents/LyricCreator/index.tsx", import.meta.url), "utf8");
  expect(source).toContain("attachCreatorLineDragging(lineEditorRef.current");
  expect(source).toContain("data-creator-line-id={line.id}");
  expect(source).toContain('data-creator-line-handle=""');
  expect(source).toContain('event.altKey || !["ArrowUp", "ArrowDown"].includes(event.key)');
  expect(source).toContain("event.dataTransfer.effectAllowed");
  expect(source).not.toContain("application/x-icy-lyric-line");
  expect(source).not.toContain("draggedLineRef");
});
