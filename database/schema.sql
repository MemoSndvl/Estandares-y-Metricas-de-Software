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
    TipoMovimiento VARCHAR(10) NOT NULL CHECK (TipoMovimiento IN ('ENTRADA', 'SALIDA')),
    Fecha TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Cantidad NUMERIC(18,4) NOT NULL CHECK (Cantidad > 0),
    CostoUnitario NUMERIC(18,4) NOT NULL CHECK (CostoUnitario >= 0),
    CostoTotal NUMERIC(18,4) GENERATED ALWAYS AS (Cantidad * CostoUnitario) STORED,
    CONSTRAINT FK_Movimientos_Productos FOREIGN KEY (ProductoId) REFERENCES Productos(Id)
);
