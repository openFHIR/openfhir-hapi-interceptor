-- Create OpenFHIR database and user
CREATE DATABASE openfhir;
CREATE USER openfhir WITH ENCRYPTED PASSWORD 'openfhir';
GRANT ALL PRIVILEGES ON DATABASE openfhir TO openfhir;

-- Ensure the users can connect and create objects within their databases
ALTER DATABASE openfhir OWNER TO openfhir;

-- Grant privileges to schemas (optional but recommended)
ALTER ROLE openfhir SET search_path TO public;