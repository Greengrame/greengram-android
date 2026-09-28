CREATE TABLE checks (
    day TEXT NOT NULL,
    install_id TEXT NOT NULL,
    package TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    PRIMARY KEY (day, install_id, package)
);
CREATE INDEX checks_package_day ON checks (package, day);

CREATE TABLE legacy_checks (
    day TEXT NOT NULL,
    package TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (day, package)
);
