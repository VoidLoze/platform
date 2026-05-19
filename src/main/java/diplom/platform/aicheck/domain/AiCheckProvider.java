package diplom.platform.aicheck.domain;

/**
 * Контракт одного AI-провайдера: оркестратор сам решает, кого вызвать (по subject-policy + fallback).
 */
public interface AiCheckProvider {
    AiProviderId id();

    boolean enabled();

    boolean supports(AiCheckSourceType sourceType);

    AiCheckOutcome check(AiCheckRequest request);
}
