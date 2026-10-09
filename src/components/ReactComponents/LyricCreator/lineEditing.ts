import { createCreatorId, type CreatorProject } from "./model.ts";

/** Move just the requested row, retaining its timing and explicit vocal attachment. */
export function moveCreatorLine(
  project: CreatorProject,
  fromId: string,
  toId: string,
  placement: "before" | "after"
): CreatorProject | null {
  if (fromId === toId || (placement !== "before" && placement !== "after")) return null;
  const fromIndex = project.lines.findIndex((line) => line.id === fromId);
  const toIndex = project.lines.findIndex((line) => line.id === toId);
  if (fromIndex < 0 || toIndex < 0) return null;

  const insertionIndex = toIndex + (placement === "after" ? 1 : 0) - (fromIndex < toIndex ? 1 : 0);
  if (insertionIndex === fromIndex) return null;
  const lines = [...project.lines];
  const [line] = lines.splice(fromIndex, 1);
  lines.splice(insertionIndex, 0, line);
  return { ...project, lines };
}

/** Duplicate a single row, not its attached rows, without sharing editable nested data. */
export function duplicateCreatorLine(
  project: CreatorProject,
  lineId: string
): { project: CreatorProject; lineId: string } | null {
  const index = project.lines.findIndex((line) => line.id === lineId);
  if (index < 0) return null;
  const source = project.lines[index];
  const usedIds = new Set(project.lines.flatMap((line) => [
    line.id,
    ...line.tokens.flatMap((token) => [token.id, ...token.fragments.map((fragment) => fragment.id)]),
  ]));
  const freshId = (prefix: string): string => {
    const base = createCreatorId(prefix);
    let id = base;
    let suffix = 1;
    // Also handle a persisted fallback ID colliding after an app restart.
    while (usedIds.has(id)) id = `${base}-${suffix++}`;
    usedIds.add(id);
    return id;
  };
  const duplicate = {
    ...source,
    id: freshId("line"),
    attachedToLineId: source.attachedToLineId === source.id ? null : source.attachedToLineId,
    tokens: source.tokens.map((token) => ({
      ...token,
      id: freshId("token"),
      fragments: token.fragments.map((fragment) => ({ ...fragment, id: freshId("fragment") })),
    })),
  };
  const lines = [...project.lines];
  lines.splice(index + 1, 0, duplicate);
  return { project: { ...project, lines }, lineId: duplicate.id };
}
