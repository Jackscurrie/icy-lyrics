import { describe, expect, it, vi } from "vitest";
import { setFrameClass, setFrameStyle } from "../src/utils/DOM/FrameWrites";

function fakeElement() {
  const classes = new Set<string>();
  const styles = new Map<string, string>();
  const toggle = vi.fn((name: string, enabled: boolean) => {
    if (enabled) classes.add(name);
    else classes.delete(name);
  });
  const setProperty = vi.fn((name: string, value: string) => styles.set(name, value));
  const removeProperty = vi.fn((name: string) => styles.delete(name));
  const element = {
    classList: { contains: (name: string) => classes.has(name), toggle },
    style: { getPropertyValue: (name: string) => styles.get(name) ?? "", setProperty, removeProperty },
  } as unknown as HTMLElement;
  return { element, classes, styles, toggle, setProperty, removeProperty };
}

describe("fullscreen frame writes", () => {
  it("does not mutate settled presentation on repeated frames", () => {
    const node = fakeElement();
    for (let frame = 0; frame < 240; frame++) {
      setFrameClass(node.element, "FocusCurrentLine", true);
      setFrameClass(node.element, "FocusPreviousLine", false);
      setFrameStyle(node.element, "--focus-bg-offset", "10cqh");
      setFrameStyle(node.element, "--fullscreen-line-transition-progress", null);
    }
    expect(node.toggle).toHaveBeenCalledTimes(1);
    expect(node.setProperty).toHaveBeenCalledTimes(1);
    expect(node.removeProperty).not.toHaveBeenCalled();
  });

  it("still updates every changed animation sample and reconciles external cleanup", () => {
    const node = fakeElement();
    for (const value of ["0", "0.5", "1"]) setFrameStyle(node.element, "--progress", value);
    expect(node.setProperty).toHaveBeenCalledTimes(3);
    node.styles.clear();
    setFrameStyle(node.element, "--progress", "1");
    expect(node.setProperty).toHaveBeenCalledTimes(4);
    setFrameClass(node.element, "FocusCurrentLine", true);
    node.classes.clear();
    setFrameClass(node.element, "FocusCurrentLine", true);
    expect(node.toggle).toHaveBeenCalledTimes(2);
    setFrameStyle(node.element, "--progress", null);
    setFrameStyle(node.element, "--progress", null);
    expect(node.removeProperty).toHaveBeenCalledTimes(1);
  });
});
