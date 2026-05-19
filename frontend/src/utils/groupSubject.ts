const SUBJECT_PREFIX = "subject::";

export type GroupMeta = {
  subject: string;
  description: string;
};

export function parseGroupMeta(rawDescription: string | null | undefined): GroupMeta {
  const source = (rawDescription ?? "").trim();
  if (!source) {
    return { subject: "", description: "" };
  }
  const [first, ...rest] = source.split("\n");
  if (first.toLowerCase().startsWith(SUBJECT_PREFIX)) {
    const subject = first.slice(SUBJECT_PREFIX.length).trim();
    const description = rest.join("\n").trim();
    return { subject, description };
  }
  return { subject: "", description: source };
}

export function buildGroupDescription(subject: string, description: string): string {
  const normalizedSubject = subject.trim();
  const normalizedDescription = description.trim();
  if (!normalizedSubject) {
    return normalizedDescription;
  }
  if (!normalizedDescription) {
    return `${SUBJECT_PREFIX}${normalizedSubject}`;
  }
  return `${SUBJECT_PREFIX}${normalizedSubject}\n${normalizedDescription}`;
}

export function groupDescriptionForView(rawDescription: string | null | undefined): string {
  return parseGroupMeta(rawDescription).description;
}
