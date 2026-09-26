CREATE TABLE classrooms (
    id UUID PRIMARY KEY,
    owner_teacher_user_id UUID NOT NULL,
    name TEXT NOT NULL,
    subject TEXT NOT NULL,
    grade TEXT NOT NULL,
    description TEXT,
    CONSTRAINT ck_classrooms_name CHECK (name ~ '[^[:space:]]'),
    CONSTRAINT ck_classrooms_subject CHECK (subject ~ '[^[:space:]]'),
    CONSTRAINT ck_classrooms_grade CHECK (grade ~ '[^[:space:]]')
);

CREATE TABLE classroom_memberships (
    id UUID PRIMARY KEY,
    classroom_id UUID NOT NULL,
    user_id UUID NOT NULL,
    CONSTRAINT fk_memberships_classroom FOREIGN KEY (classroom_id) REFERENCES classrooms(id),
    CONSTRAINT uq_memberships_classroom_user UNIQUE (classroom_id, user_id)
);

CREATE TABLE pending_classroom_invitations (
    id UUID PRIMARY KEY,
    classroom_id UUID NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    invited_full_name TEXT NOT NULL,
    CONSTRAINT fk_invitations_classroom FOREIGN KEY (classroom_id) REFERENCES classrooms(id),
    CONSTRAINT uq_invitations_classroom_email UNIQUE (classroom_id, normalized_email),
    CONSTRAINT ck_invitations_email CHECK (
        normalized_email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
        AND split_part(normalized_email, '@', 2) = lower(split_part(normalized_email, '@', 2))
    ),
    CONSTRAINT ck_invitations_full_name CHECK (invited_full_name ~ '[^[:space:]]')
);
