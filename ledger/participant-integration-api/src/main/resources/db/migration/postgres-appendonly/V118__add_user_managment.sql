--  Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
--  SPDX-License-Identifier: Apache-2.0



CREATE TABLE participant_user (
    internal_id         SERIAL          NOT NULL PRIMARY KEY,
    user_id             TEXT            NOT NULL UNIQUE,
    primary_party       TEXT,
    created_at          BIGINT          NOT NULL -- TODO: automatic epoch timestamps? https://x-team.com/blog/automatic-timestamps-with-postgresql/ - do it in JVM
);

CREATE TABLE user_rights (
    user_internal_id    INTEGER         NOT NULL REFERENCES participant_user (internal_id) ON DELETE CASCADE,
    user_right          INTEGER         NOT NULL,
    for_party           TEXT,
    granted_at          BIGINT          NOT NULL,
    UNIQUE (user_internal_id, user_right, for_party)
);



--participant_admin = (1, NULL)
--canActAs(“alice”) = (2, “alice”)
--canReadAs(“alice”) = (3, “alice”)
