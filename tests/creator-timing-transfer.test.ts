import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { createEmptyProject, importPlainText, type CreatorFragment } from "../src/components/ReactComponents/LyricCreator/model.ts";
import { attachCreatorTimingTransfer, canCopyCreatorFragmentTiming, copyCreatorFragmentTiming } from "../src/components/ReactComponents/LyricCreator/timingTransfer.ts";

function fixture() {
  const project = createEmptyProject("spotify:track:example");
  project.lines = importPlainText("source spare\ntarget other");
  const [source, spare, target, other] = project.lines.flatMap((line) => line.tokens.flatMap((token) => token.fragments));
  Object.assign(source, { startTimeMs: 1200, endTimeMs: 1800 });
  Object.assign(spare, { startTimeMs: 2100, endTimeMs: 2500 });
  Object.assign(target, { startTimeMs: 4000, endTimeMs: 4200 });
  Object.assign(other, { startTimeMs: 4500, endTimeMs: 5000 });
  project.lines[0].startTimeMs = 1200;
  project.lines[0].endTimeMs = 2500;
  project.lines[1].startTimeMs = 4000;
  project.lines[1].endTimeMs = 5000;
  return { project, source, spare, target, other };
}

describe("creator fragment timing copies", () => {
  it("copies timings across lines without changing the source, target text, identities or metadata", () => {
    const { project, source, target } = fixture();
    const before = structuredClone(project);
    const copied = copyCreatorFragmentTiming(project, source.id, target.id)!;
    expect(project).toEqual(before);
    expect(copied).not.toBe(project);
    expect(copied.metadata).toBe(project.metadata);
    expect(copied.lines[0]).toBe(project.lines[0]);
    expect(copied.lines[1].tokens[1]).toBe(project.lines[1].tokens[1]);
    expect(copied.lines[1].tokens[0].fragments[0]).toEqual({ ...target, startTimeMs: 1200, endTimeMs: 1800 });
    expect(copied.lines[1]).toMatchObject({ startTimeMs: 1200, endTimeMs: 5000 });
  });

  it("updates bounds when replacing the final/latest timed fragment within a line", () => {
    const { project, source, spare } = fixture();
    const copied = copyCreatorFragmentTiming(project, source.id, spare.id)!;
    expect(copied.lines[0]).toMatchObject({ startTimeMs: 1200, endTimeMs: 1800 });
    expect(copied.lines[0].tokens[0].fragments[0]).toBe(source);
  });

  it.each([
    [3000, null],
    [null, 3500],
    [0, 0],
  ])("copies partial/zero timing pairs exactly: %s, %s", (startTimeMs, endTimeMs) => {
    const { project, source, target } = fixture();
    project.lines[1].tokens = [project.lines[1].tokens[0]];
    Object.assign(source, { startTimeMs, endTimeMs });
    const copied = copyCreatorFragmentTiming(project, source.id, target.id)!;
    expect(copied.lines[1].tokens[0].fragments[0]).toMatchObject({ startTimeMs, endTimeMs });
    expect(copied.lines[1]).toMatchObject({ startTimeMs, endTimeMs });
  });

  it.each([
    [null, null], [-1, 2], [NaN, 2], [0, Infinity], [3000, 1000],
  ])("refuses untimed or invalid sources: %s, %s", (startTimeMs, endTimeMs) => {
    const { project, source, target } = fixture();
    Object.assign(source, { startTimeMs, endTimeMs });
    expect(canCopyCreatorFragmentTiming(source)).toBe(false);
    expect(copyCreatorFragmentTiming(project, source.id, target.id)).toBeNull();
  });

  it("returns no-op for the same fragment, missing IDs, or an already identical timing pair", () => {
    const { project, source, target } = fixture();
    expect(copyCreatorFragmentTiming(project, source.id, source.id)).toBeNull();
    expect(copyCreatorFragmentTiming(project, "missing", target.id)).toBeNull();
    expect(copyCreatorFragmentTiming(project, source.id, "missing")).toBeNull();
    Object.assign(target, { startTimeMs: source.startTimeMs, endTimeMs: source.endTimeMs });
    expect(copyCreatorFragmentTiming(project, source.id, target.id)).toBeNull();
  });
});

class FakeEvents {
  listeners = new Map<string, Set<(event: any) => void>>();
  addEventListener(type: string, listener: (event: any) => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(listener);
  }
  removeEventListener(type: string, listener: (event: any) => void) {
    this.listeners.get(type)?.delete(listener);
  }
  dispatch(type: string, values: Record<string, any> = {}) {
    const event = { target: this, pointerId: 1, button: 0, isPrimary: true, clientX: 10, clientY: 10,
      preventDefault: vi.fn(), stopImmediatePropagation: vi.fn(), ...values };
    for (const listener of this.listeners.get(type) ?? []) listener(event);
    return event;
  }
}

class FakeElement extends FakeEvents {
  attrs = new Map<string, string>();
  children: FakeElement[] = [];
  parent: FakeElement | null = null;
  style: Record<string, string> = {};
  className = "";
  textContent = "";
  capture: number | null = null;
  constructor(public ownerDocument: any) { super(); }
  setAttribute(name: string, value: string) { this.attrs.set(name, value); }
  getAttribute(name: string) { return this.attrs.get(name) ?? null; }
  removeAttribute(name: string) { this.attrs.delete(name); }
  append(...elements: FakeElement[]) {
    for (const element of elements) { element.parent = this; this.children.push(element); }
  }
  remove() {
    if (this.parent) this.parent.children = this.parent.children.filter((child) => child !== this);
    this.parent = null;
  }
  contains(element: FakeElement): boolean {
    return element === this || this.children.some((child) => child.contains(element));
  }
  closest(): FakeElement | null { return this.attrs.has("data-creator-timing-fragment") ? this : this.parent?.closest() ?? null; }
  getBoundingClientRect() { return { left: 50, top: 100 }; }
  setPointerCapture(id: number) { this.capture = id; }
  hasPointerCapture(id: number) { return this.capture === id; }
  releasePointerCapture() { this.capture = null; }
}

function pointerFixture(reducedMotion = false) {
  const view = Object.assign(new FakeEvents(), {
    setTimeout, clearTimeout,
    requestAnimationFrame: vi.fn(() => 1), cancelAnimationFrame: vi.fn(),
    matchMedia: () => ({ matches: reducedMotion }),
  });
  const doc: any = { defaultView: view, fullscreenElement: null, hit: null,
    elementFromPoint: () => doc.hit, createElement: () => new FakeElement(doc) };
  doc.body = new FakeElement(doc);
  const sheet = new FakeElement(doc);
  const source = new FakeElement(doc);
  const target = new FakeElement(doc);
  source.setAttribute("data-creator-timing-fragment", "source");
  target.setAttribute("data-creator-timing-fragment", "target");
  sheet.append(source, target);
  doc.body.append(sheet);
  const fragment: CreatorFragment = { id: "source", text: "hello", startTimeMs: 100, endTimeMs: 200 };
  const onCopyTiming = vi.fn();
  const dispose = attachCreatorTimingTransfer(sheet as unknown as HTMLElement, {
    getFragment: (id) => id === "source" ? fragment : undefined, onCopyTiming,
    formatTime: (value) => String(value),
  });
  return { view, doc, sheet, source, target, fragment, onCopyTiming, dispose };
}

describe("timing drag pointer lifecycle", () => {
  it("keeps small movements as ordinary clicks and does not copy until dragged", () => {
    const { view, sheet, source, target, doc, onCopyTiming, dispose } = pointerFixture();
    sheet.dispatch("pointerdown", { target: source });
    view.dispatch("pointermove", { clientX: 13, clientY: 12 });
    doc.hit = target;
    view.dispatch("pointerup");
    expect(onCopyTiming).not.toHaveBeenCalled();
    expect(sheet.dispatch("click", { detail: 1 }).preventDefault).not.toHaveBeenCalled();
    expect(doc.body.children).toHaveLength(1);
    dispose();
  });

  it("copies across cells on release and suppresses only the generated pointer click", () => {
    const { view, sheet, source, target, doc, onCopyTiming, dispose } = pointerFixture();
    sheet.dispatch("pointerdown", { target: source });
    doc.hit = target;
    view.dispatch("pointermove", { clientX: 90 });
    expect(doc.body.children).toHaveLength(2);
    expect(source.attrs.has("data-timing-drag-source")).toBe(true);
    expect(target.attrs.has("data-timing-drop-target")).toBe(true);
    view.dispatch("pointerup", { clientX: 90 });
    expect(onCopyTiming).toHaveBeenCalledExactlyOnceWith("source", "target");
    expect(source.capture).toBeNull();
    expect(source.attrs.has("data-timing-drag-source")).toBe(false);
    expect(target.attrs.has("data-timing-drop-target")).toBe(false);
    expect(sheet.dispatch("click", { detail: 0 }).preventDefault).not.toHaveBeenCalled();
    expect(sheet.dispatch("click", { detail: 1 }).preventDefault).toHaveBeenCalled();
    dispose();
    expect(doc.body.children).toHaveLength(1);
    expect([...view.listeners.values()].every((listeners) => listeners.size === 0)).toBe(true);
  });

  it.each(["pointercancel", "Escape", "blur", "dispose"])("cleans up without copying on %s", (action) => {
    const { view, sheet, source, target, doc, onCopyTiming, dispose } = pointerFixture();
    sheet.dispatch("pointerdown", { target: source });
    doc.hit = target;
    view.dispatch("pointermove", { clientX: 90 });
    if (action === "Escape") {
      const event = view.dispatch("keydown", { key: "Escape" });
      expect(event.stopImmediatePropagation).toHaveBeenCalled();
    } else if (action === "dispose") dispose();
    else view.dispatch(action);
    expect(onCopyTiming).not.toHaveBeenCalled();
    expect(source.capture).toBeNull();
    expect(doc.body.children).toHaveLength(1);
    expect(target.attrs.has("data-timing-drop-target")).toBe(false);
    dispose();
  });

  it("does not drag an untimed source or copy onto itself", () => {
    const fixture = pointerFixture();
    fixture.fragment.startTimeMs = null;
    fixture.fragment.endTimeMs = null;
    fixture.sheet.dispatch("pointerdown", { target: fixture.source });
    fixture.view.dispatch("pointermove", { clientX: 90 });
    expect(fixture.doc.body.children).toHaveLength(1);
    fixture.fragment.startTimeMs = 0;
    fixture.sheet.dispatch("pointerdown", { target: fixture.source });
    fixture.doc.hit = fixture.source;
    fixture.view.dispatch("pointermove", { clientX: 90 });
    fixture.view.dispatch("pointerup");
    expect(fixture.onCopyTiming).not.toHaveBeenCalled();
    fixture.dispose();
  });

  it("ignores other pointers and does not copy when released outside a timing cell", () => {
    const { view, sheet, source, target, doc, onCopyTiming, dispose } = pointerFixture();
    sheet.dispatch("pointerdown", { target: source });
    doc.hit = target;
    view.dispatch("pointermove", { pointerId: 2, clientX: 90 });
    expect(doc.body.children).toHaveLength(1);
    view.dispatch("pointermove", { clientX: 90 });
    view.dispatch("pointerup", { pointerId: 2 });
    expect(onCopyTiming).not.toHaveBeenCalled();
    expect(doc.body.children).toHaveLength(2);
    doc.hit = null;
    view.dispatch("pointerup");
    expect(onCopyTiming).not.toHaveBeenCalled();
    expect(doc.body.children).toHaveLength(1);
    dispose();
  });

  it.each([{ button: 2 }, { isPrimary: false }])("ignores non-left/non-primary pointer: %j", (values) => {
    const { view, sheet, source, target, doc, onCopyTiming, dispose } = pointerFixture();
    sheet.dispatch("pointerdown", { target: source, ...values });
    doc.hit = target;
    view.dispatch("pointermove", { clientX: 90 });
    view.dispatch("pointerup");
    expect(onCopyTiming).not.toHaveBeenCalled();
    expect(doc.body.children).toHaveLength(1);
    dispose();
  });

  it("has no lingering landing animation in reduced motion", () => {
    const { view, sheet, source, target, doc, onCopyTiming, dispose } = pointerFixture(true);
    sheet.dispatch("pointerdown", { target: source });
    doc.hit = target;
    view.dispatch("pointermove", { clientX: 90 });
    view.dispatch("pointerup");
    expect(onCopyTiming).toHaveBeenCalledExactlyOnceWith("source", "target");
    expect(doc.body.children).toHaveLength(1);
    dispose();
  });
});

it("wires fragment identities and immediate whole-song offset guidance into the timing workspace", () => {
  const source = readFileSync(new URL("../src/components/ReactComponents/LyricCreator/CreatorTimeWorkspace.tsx", import.meta.url), "utf8");
  const css = readFileSync(new URL("../src/components/ReactComponents/LyricCreator/timing-transfer.css", import.meta.url), "utf8");
  expect(source).toContain("data-creator-timing-fragment={fragment.id}");
  expect(source).toContain("return attachCreatorTimingTransfer(sheetRef.current");
  expect(source).toContain("onCopyTiming(sourceId, targetId)");
  expect(source).toContain("Shifts all song timings immediately, including the preview");
  expect(source).not.toContain("Applied when F, G, or H is pressed");
  expect(css).toContain("pointer-events: none");
  expect(css).toContain("prefers-reduced-motion: reduce");
  expect(css).not.toContain("is-playing");
  expect(css).not.toContain("aria-selected");
});
