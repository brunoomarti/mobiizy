# Supabase Storage Setup (Firebase + Supabase)

Este projeto continua usando Firebase normalmente e adiciona Supabase apenas para Storage.

## 1. Dependencias ja adicionadas

- `io.github.jan-tennert.supabase:bom:2.2.3`
- `io.github.jan-tennert.supabase:storage-kt`
- `io.ktor:ktor-client-android:2.3.12`

## 2. Configurar credenciais (recomendado: `gradle.properties`)

Voce pode colocar em:

- `gradle.properties` (raiz do projeto) **recomendado**
- `local.properties` (raiz)
- variaveis de ambiente

Exemplo:

```properties
SUPABASE_URL=https://SEU-PROJETO.supabase.co
SUPABASE_ANON_KEY=SEU_ANON_KEY
SUPABASE_BUCKET=vehicle-media
```

Aliases aceitos para compatibilidade:

- URL: `SUPABASE_URL`, `SUPABASE_PROJECT_URL`, `supabase.url`
- KEY: `SUPABASE_ANON_KEY`, `SUPABASE_KEY`, `supabase.anon.key`
- BUCKET: `SUPABASE_BUCKET`, `supabase.bucket`

Esses valores vao para o `BuildConfig`:

- `BuildConfig.SUPABASE_URL`
- `BuildConfig.SUPABASE_ANON_KEY`
- `BuildConfig.SUPABASE_BUCKET`

## 3. Criar bucket no Supabase

No painel do Supabase:

1. Storage -> Create bucket
2. Nome: `vehicle-media` (ou o mesmo de `SUPABASE_BUCKET`)
3. Defina como publico se quiser URL publica direta.

## 4. Politicas (RLS) basicas (exemplo)

Se o bucket for privado, crie policies de acordo com sua regra de negocio.
Exemplo simples para usuarios autenticados:

```sql
-- leitura
create policy "Authenticated users can read objects"
on storage.objects
for select
to authenticated
using (bucket_id = 'vehicle-media');

-- upload
create policy "Authenticated users can upload objects"
on storage.objects
for insert
to authenticated
with check (bucket_id = 'vehicle-media');
```

## 5. Servico pronto no app

Arquivo:

- `app/src/main/java/com/brunocodex/kotlinproject/services/SupabaseStorageService.kt`

Metodos principais:

- `isReady()`
- `uploadVehiclePhoto(localFile, ownerId, vehiclePlate, mediaCategory)`
- `uploadVehiclePhotoByPlate(ownerId, vehiclePlate, mediaCategory, bytes, fileExtension)`
- `uploadBytes(remotePath, bytes, upsert)`
- `publicUrl(remotePath)`

## 6. Exemplo rapido de uso

```kotlin
val url = SupabaseStorageService.uploadVehiclePhoto(
    localFile = photoFile,
    ownerId = "uid123",
    vehicleId = "car456",
    mediaCategory = "front"
)
```

`url` retorna a URL publica do arquivo salvo no bucket.

Os uploads de veiculo agora seguem este padrao de pasta:

`clients/{cliente}/{placa}/{categoria}_{timestamp}.{ext}`
