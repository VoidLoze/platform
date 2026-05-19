package diplom.platform.identity.domain;

import java.util.Objects;

public record FullName(String firstName, String lastName, String middleName) {
    public FullName {
        Objects.requireNonNull(firstName, "firstName is required");
        Objects.requireNonNull(lastName, "lastName is required");
    }

    public String getFullName() {
        return String.join(" ",
                firstName,
                middleName == null ? "" : middleName,
                lastName).trim().replaceAll("\\s+", " ");
    }
}
