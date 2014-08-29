-- Author: dan.leehr@nescent.org
--

-- Organizations
CREATE SEQUENCE organization_seq;
CREATE TABLE organization
(
  organization_id INTEGER PRIMARY KEY not null default nextval('organization_seq'),
  code VARCHAR(32) not null,
  name VARCHAR(255) not null
);

CREATE UNIQUE INDEX org_code_idx on organization(code);

-- Manuscripts
CREATE SEQUENCE manuscript_seq;
CREATE TABLE manuscript
(
  manuscript_id INTEGER PRIMARY KEY not null default nextval('manuscript_seq'),
  organization_id INTEGER not null REFERENCES organization(organization_id),
  msid VARCHAR(255) not null,
  version INTEGER not null,
  json_data text 
);

CREATE UNIQUE INDEX manuscript_msid_ver_idx on manuscript(msid, version);
CREATE INDEX manuscript_msid_idx ON manuscript(msid);

-- Manuscript versions
CREATE SEQUENCE manuscript_active_version_seq;
CREATE TABLE manuscript_active_version
(
  manuscript_active_version_id INTEGER PRIMARY KEY not null default nextval('manuscript_active_version_seq'),
  manuscript_id INTEGER not null REFERENCES manuscript(manuscript_id),
  version INTEGER not null
);

CREATE UNIQUE INDEX manuscript_ver_ms_id_idx on manuscript_active_version(manuscript_id);

-- OAuth2 Tokens
CREATE SEQUENCE oauth_token_seq;
CREATE TABLE oauth_token
(
  oauth_token_id INTEGER PRIMARY KEY not null default nextval('oauth_token_seq'),
  eperson_id INTEGER not null REFERENCES eperson(eperson_id),
  token VARCHAR(32) not null,
  expires DATE
);

-- API users and permissions
-- should these just be tied to dspace resource policy?