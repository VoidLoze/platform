import type { ChatMessage, ChatReadReceipt } from "../../types";

/**
 * Показываем «прочитано» только на своих сообщениях, если у кого-то из остальных
 * указатель последнего прочитанного сообщения дошёл до этого или дальше по ленте.
 */
export function shouldShowReadReceipt(
  msg: ChatMessage,
  messagesOrdered: ChatMessage[],
  receipts: ChatReadReceipt[],
  myUserId: string,
): boolean {
  if (msg.senderId !== myUserId) {
    return false;
  }
  const msgIndex = messagesOrdered.findIndex((m) => m.id === msg.id);
  if (msgIndex < 0) {
    return false;
  }
  const others = receipts.filter((r) => r.userId !== myUserId);
  for (const r of others) {
    const readIdx = messagesOrdered.findIndex((m) => m.id === r.messageId);
    if (readIdx >= msgIndex) {
      return true;
    }
  }
  return false;
}
