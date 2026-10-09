import type { CreatorProject } from "./model.ts";

export interface CreatorBackgroundIssue {
  lineIndex: number;
  lineId: string;
  message: string;
}

/** Use the same explicit-or-previous-lead rule in the editor and TTML export. */
export function analyzeCreatorBackgroundAttachments(project: CreatorProject) {
  const leadIds = new Set(project.lines.filter((line) => !line.isBackground).map((line) => line.id));
  const targetByLineId = new Map<string, string | null>();
  const backgroundsByLeadId = new Map<string, string[]>();
  const issues: CreatorBackgroundIssue[] = [];
  let lastLeadId: string | null = null;

  project.lines.forEach((line, lineIndex) => {
    if (!line.isBackground) {
      lastLeadId = line.id;
      return;
    }
    const targetId = line.attachedToLineId ?? lastLeadId;
    targetByLineId.set(line.id, targetId);
    let message: string | undefined;
    if (!targetId) {
      message = `Background line ${lineIndex + 1} is not attached to a lead line.`;
    } else if (!leadIds.has(targetId)) {
      message = `Background line ${lineIndex + 1} must reference a lead line in this project.`;
    } else {
      const backgrounds = backgroundsByLeadId.get(targetId) ?? [];
      backgrounds.push(line.id);
      backgroundsByLeadId.set(targetId, backgrounds);
      if (backgrounds.length > 1) {
        message = `Line ${lineIndex + 1} is a second background row for the same lead. ` +
          "Choose a different lead below, or make this a lead line.";
      }
    }
    if (message) issues.push({ lineIndex, lineId: line.id, message });
  });

  return { targetByLineId, backgroundsByLeadId, issues };
}

/** Keep the row and every timing intact; null promotes it to an ordinary lead. */
export function repairCreatorBackgroundAttachment(
  project: CreatorProject,
  lineId: string,
  targetId: string | null
): CreatorProject | null {
  const source = project.lines.find((line) => line.id === lineId);
  if (!source?.isBackground) return null;
  if (targetId !== null && !project.lines.some((line) => line.id === targetId && !line.isBackground)) {
    return null;
  }

  const before = analyzeCreatorBackgroundAttachments(project);
  const next = {
    ...project,
    lines: project.lines.map((line) => line.id === lineId
      ? { ...line, isBackground: targetId !== null, attachedToLineId: targetId }
      : line),
  };
  // Promoting a row must not silently steal following legacy/implicit BG rows
  // from their original lead. Only pin the attachments this repair would change.
  if (targetId === null) {
    const after = analyzeCreatorBackgroundAttachments(next);
    next.lines = next.lines.map((line) => {
      if (!line.isBackground || line.id === lineId || line.attachedToLineId) return line;
      const originalTarget = before.targetByLineId.get(line.id);
      if (!originalTarget || after.targetByLineId.get(line.id) === originalTarget) return line;
      return { ...line, attachedToLineId: originalTarget };
    });
  }
  return next;
}
