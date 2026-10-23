# Sistema de inventario en Java

Aplicación de escritorio para administrar productos y movimientos de inventario. Guarda las transacciones en PostgreSQL de Supabase y permite consultar la valoración PEPS o por promedio ponderado móvil sin cambiar el historial.

## Requisitos

- JDK 17 o posterior.
- Maven 3.9 o posterior.
- Proyecto Supabase con acceso a PostgreSQL.

## Configuración de Supabase

1. En Supabase, abre **SQL Editor** y ejecuta [`database/schema.sql`](database/schema.sql).
2. En **Project Settings > Database**, abre **Connection string** y localiza la contraseña de PostgreSQL. No es la clave publishable ni la clave secreta de API. Para los datos de conexión que compartiste, la URL JDBC es `jdbc:postgresql://db.qhdsnxctkcyzgqzhffak.supabase.co:5432/postgres?sslmode=require` y el usuario es `postgres`.
3. Opcionalmente, define la URL y el usuario en la terminal:

   ```powershell
   $env:SUPABASE_DB_URL = "jdbc:postgresql://db.qhdsnxctkcyzgqzhffak.supabase.co:5432/postgres?sslmode=require"
   $env:SUPABASE_DB_USER = "postgres"
   ```

   La URL y el usuario del Session pooler ya están predeterminados en la aplicación. Para iniciar la interfaz gráfica, configura `SUPABASE_DB_PASSWORD` como variable de entorno de Windows antes de abrir VS Code; la app la usa para conectarse en segundo plano y ya no se la solicita a cada persona. No se guarda en el código. En modo consola, si no está definida, se solicita en la terminal.

   No uses la clave API de Supabase como contraseña de PostgreSQL.

4. Desde la raíz del proyecto, ejecuta:

   ```powershell
   mvn test
   mvn package
   mvn exec:java -Dexec.mainClass="com.example.inventory.Main"
   ```

   El comando inicia la interfaz gráfica. Para iniciar la aplicación de consola, añade `-Dexec.args="--console"` al comando de Maven.

Al iniciar, la aplicación comprueba la conexión y que existan `public.productos`, `public.movimientos` y `public.usuarios`. Si no encuentra las tablas, ejecuta el esquema del paso 1; si no conecta, revisa la contraseña PostgreSQL y el acceso de red.

## Uso

La interfaz presenta navegación lateral, tarjetas de resumen y tablas de inventario. Permite consultar existencias y su valor, registrar entradas y salidas, y revisar el historial. Al crear o editar productos, la unidad se elige de un combo con opciones comunes (pza, kg, g, L, ml, m, caja, paquete, bolsa y docena, entre otras), que también permite escribir una unidad personalizada. Para caja, paquete, bolsa y docena, indica las piezas que contiene cada empaque. Las cantidades capturadas como empaques se convierten automáticamente a piezas; inventario e historial se muestran en la unidad base. El costo de entrada se captura por pieza. Cada producto tiene una categoría, que puede usarse para filtrar el combo de productos en **Kardex promedio**. El kardex presenta compras, ventas y saldo corrido (cantidad, costo unitario y costo total) con promedio ponderado móvil. Las salidas requieren seleccionar PEPS o promedio ponderado.

El inicio de sesión muestra un panel dividido con formulario de acceso y un área de bienvenida en tonos azul y cian.

En el primer inicio, se crea una cuenta administradora de la aplicación. El administrador puede registrar y modificar productos, consultar todo el inventario, registrar movimientos con fecha elegida y administrar usuarios. Puede crear cuentas de administrador o trabajador y activar/desactivar cuentas.

El trabajador puede consultar productos, existencias e historial, y registrar entradas y salidas. No puede crear ni modificar productos, administrar usuarios ni elegir la fecha de los movimientos: se asigna automáticamente la fecha actual UTC. Los movimientos registrados no se pueden editar ni eliminar, tampoco por el administrador; para conservar la trazabilidad, una corrección requiere registrar el movimiento correspondiente.

Las contraseñas de ambos roles aceptan cualquier cantidad de caracteres, siempre que no estén vacías, y se almacenan con hash PBKDF2 y salt. Desde **Configuración de usuario**, cada persona puede cambiar su nombre de acceso y contraseña (confirmando la contraseña actual), y guardar su preferencia de modo noche para la interfaz gráfica. El inicio de sesión y los permisos se aplican tanto en la interfaz gráfica como en el modo consola.

Si ya tenías las tablas creadas, vuelve a ejecutar `database/schema.sql` en el SQL Editor de Supabase para agregar `Categoria` y crear `Usuarios`; los productos existentes se asignan automáticamente a **General**. La aplicación agrega automáticamente las columnas de preferencia de modo noche y equivalencia del empaque al iniciar. Los productos existentes reciben equivalencia 1; antes de registrar movimientos en empaques, edita el producto y guarda cuántas piezas contiene.

Las cuentas y roles son un inicio de sesión de la aplicación, no reemplazan la contraseña de PostgreSQL: la app todavía necesita `SUPABASE_DB_PASSWORD` para abrir la conexión compartida. Los permisos de rol se aplican dentro de la aplicación; no son controles de seguridad contra una persona que acceda directamente a la base de datos.

Los productos inactivos no aceptan nuevos movimientos. Los movimientos existentes no se editan ni eliminan, por lo que ambos métodos de costeo siempre se calculan sobre el mismo historial.

## Criterios de cálculo

- Cantidades: decimal de hasta 4 posiciones.
- Costos unitarios de entrada: decimal de hasta 4 posiciones.
- Promedio móvil: se redondea a 8 decimales con `HALF_UP` al recalcular cada entrada; las salidas no alteran el promedio unitario restante.
- Importes de salidas y valor del inventario: se muestran con 2 decimales y redondeo `HALF_UP`.
- La aplicación rechaza cantidades y costos con más de 14 dígitos enteros o 4 decimales; no permite que PostgreSQL redondee silenciosamente una entrada.
- En el esquema solicitado, `CostoUnitario` es obligatorio también para las salidas. La aplicación guarda cero como valor técnico en ese campo y calcula el costo real de salida desde el historial al consultar PEPS o promedio; `CostoTotal` generado no se usa como costo de salida.
- Los cálculos usan `BigDecimal`, no `float` o `double`.
- El orden para aplicar los movimientos es fecha y, en caso de empate, ID de base de datos.

## Pruebas

`mvn test` ejecuta pruebas unitarias de costeo y validación, además de pruebas de integración del repositorio JDBC contra H2 en modo PostgreSQL. El escenario obligatorio de 10 unidades a $20, 10 a $30 y una salida de 5 se verifica con ambos métodos. La prueba H2 verifica la lógica JDBC y la persistencia dentro de la base de pruebas; una prueba de conexión real a Supabase requiere credenciales y conectividad configuradas en el entorno.

Los casos y la relación con requisitos se documentan en [`docs/casos-de-prueba.md`](docs/casos-de-prueba.md). El resultado ejecutado y los defectos se registran en [`docs/defectos.md`](docs/defectos.md).
