-- ==============================================================================
-- FSE Capstone: Oracle XE Database Bootstrap Script
-- Container: oracle-xe-master
-- Pluggable Database: XEPDB1
-- ==============================================================================
-- Pure environment bootstrap script: Creates secondary schemas and establishes
-- user grants. All DDL table creations, views, synonyms, and data lifecycle
-- are authoritatively managed by Flyway (classpath:db/migration/oracle).
-- ==============================================================================

ALTER SESSION SET CONTAINER = XEPDB1;

-- Ensure fse_user has complete schema management privileges and cross-schema synonym authority
GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SYNONYM, CREATE ANY SYNONYM, DROP ANY SYNONYM, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE TO fse_user;
ALTER USER fse_user QUOTA UNLIMITED ON USERS;

-- Secondary schemas for service synonym routing
CREATE USER CORE IDENTIFIED BY "CorePass123#";
GRANT CREATE SESSION, CREATE SYNONYM TO CORE;

CREATE USER INTEGRATION IDENTIFIED BY "IntegrationPass123#";
GRANT CREATE SESSION, CREATE SYNONYM TO INTEGRATION;

CREATE USER AUTH_IDENTITY IDENTIFIED BY "AuthIdentityPass123#";
GRANT CREATE SESSION, CREATE SYNONYM TO AUTH_IDENTITY;

-- Re-point default session to fse_user
ALTER SESSION SET CURRENT_SCHEMA = fse_user;
