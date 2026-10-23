# Registro de defectos

## Resultado

No se identificaron defectos funcionales pendientes durante las pruebas automatizadas ejecutadas.

Durante el desarrollo, una expectativa de prueba PEPS se había calculado incorrectamente: consumir 3 unidades a $10 y 2 a $12 cuesta $54, no $58. Se corrigió el dato esperado; la implementación PEPS estaba realizando el cálculo correcto. No se registra como defecto del producto.

## Regresión

Después de corregir las expectativas y ampliar los casos de transacción, se ejecutó la suite completa: 11 pruebas aprobadas, 0 fallos, 0 errores y 0 omitidas.

## Pendiente de evidencia externa

La conexión y la persistencia en un proyecto Supabase real aún requieren ejecutar el script SQL, configurar las variables de entorno y correr la aplicación con acceso a la base. Esto es una validación no realizada, no un defecto confirmado.
