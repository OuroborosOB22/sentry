CREATE TABLE projects (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    path VARCHAR(500) NOT NULL,
    ecosystem VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE snapshots (
    id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    eligibility VARCHAR(20) NOT NULL,
    profile_id VARCHAR(64) NOT NULL,
    manifest_sha256 VARCHAR(64) NOT NULL,
    folder VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE jobs (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    operation VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    request_json CLOB NOT NULL,
    execution_provider VARCHAR(20) NOT NULL,
    verdict VARCHAR(30),
    evidence_path VARCHAR(500),
    error VARCHAR(1000),
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 2,
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    stage VARCHAR(100),
    remote_id VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX jobs_status_created ON jobs (status, created_at);

CREATE TABLE findings (
    job_id VARCHAR(64) NOT NULL,
    ordinal INT NOT NULL,
    code VARCHAR(80) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    PRIMARY KEY (job_id, ordinal),
    FOREIGN KEY (job_id) REFERENCES jobs (id)
);

CREATE TABLE explanations (
    job_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(100) NOT NULL,
    body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (job_id, fingerprint)
);

CREATE TABLE settings (
    name VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL
);
