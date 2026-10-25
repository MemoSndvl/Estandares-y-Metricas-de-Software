-- Crear tabla de Productos
CREATE TABLE IF NOT EXISTS Productos (
    Id SERIAL PRIMARY KEY,
    Clave VARCHAR(50) NOT NULL UNIQUE,
    Nombre VARCHAR(100) NOT NULL,
    Descripcion VARCHAR(255) NULL,
    UnidadMedida VARCHAR(50) NOT NULL,
    Categoria VARCHAR(80) NOT NULL DEFAULT 'General',
    PiezasPorEmpaque INTEGER NOT NULL DEFAULT 1 CHECK (PiezasPorEmpaque > 0),
    Estado BOOLEAN NOT NULL DEFAULT TRUE
);

-- Permite aplicar el esquema también a instalaciones que ya tenían productos.
ALTER TABLE Productos
ADD COLUMN IF NOT EXISTS Categoria VARCHAR(80) NOT NULL DEFAULT 'General';

ALTER TABLE Productos
ADD COLUMN IF NOT EXISTS PiezasPorEmpaque INTEGER NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS Usuarios (
    Id BIGSERIAL PRIMARY KEY,
    Nombre_Usuario VARCHAR(50) NOT NULL UNIQUE,
    Sal VARCHAR(64) NOT NULL,
    Hash_Contrasena VARCHAR(128) NOT NULL,
    Rol VARCHAR(20) NOT NULL CHECK (Rol IN ('ADMIN', 'TRABAJADOR')),
    Activo BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE Usuarios
ADD COLUMN IF NOT EXISTS Modo_Oscuro BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE Usuarios ENABLE ROW LEVEL SECURITY;

-- Crear tabla de Movimientos
CREATE TABLE IF NOT EXISTS Movimientos (
    Id SERIAL PRIMARY KEY,
    ProductoId INT NOT NULL,
    TipoMovimiento VARCHAR(20) NOT NULL
        CONSTRAINT ck_movimientos_tipo
        CHECK (TipoMovimiento IN ('ENTRADA', 'SALIDA', 'AJUSTE_ENTRADA', 'AJUSTE_SALIDA')),
    Fecha TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Cantidad NUMERIC(18,4) NOT NULL CHECK (Cantidad > 0),
    CostoUnitario NUMERIC(18,4) NOT NULL CHECK (CostoUnitario >= 0),
    CostoTotal NUMERIC(18,4) GENERATED ALWAYS AS (Cantidad * CostoUnitario) STORED,
    UsuarioId BIGINT NULL REFERENCES Usuarios(Id),
    Motivo VARCHAR(255) NULL,
    CONSTRAINT FK_Movimientos_Productos FOREIGN KEY (ProductoId) REFERENCES Productos(Id)
);

-- Actualiza instalaciones existentes; los movimientos anteriores conservan sus datos y quedan sin autor/motivo.
ALTER TABLE Movimientos
ALTER COLUMN TipoMovimiento TYPE VARCHAR(20);

ALTER TABLE Movimientos
DROP CONSTRAINT IF EXISTS movimientos_tipomovimiento_check;

ALTER TABLE Movimientos
DROP CONSTRAINT IF EXISTS ck_movimientos_tipo;

ALTER TABLE Movimientos
ADD CONSTRAINT ck_movimientos_tipo
CHECK (TipoMovimiento IN ('ENTRADA', 'SALIDA', 'AJUSTE_ENTRADA', 'AJUSTE_SALIDA'));

ALTER TABLE Movimientos
ADD COLUMN IF NOT EXISTS UsuarioId BIGINT NULL REFERENCES Usuarios(Id);

ALTER TABLE Movimientos
ADD COLUMN IF NOT EXISTS Motivo VARCHAR(255) NULL;

CREATE INDEX IF NOT EXISTS idx_movimientos_fecha_id
ON Movimientos (Fecha DESC, Id DESC);
