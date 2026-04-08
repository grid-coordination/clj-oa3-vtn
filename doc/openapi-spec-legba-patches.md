# OpenAPI Spec Patches for Legba Compatibility

The OpenADR 3.1.0 specification (`openadr3-specification/3.1.0/openadr3.yaml`) declares itself as OpenAPI 3.0.0 format. Legba requires OpenAPI 3.1.x. Two changes are needed when copying the spec into this project's `resources/openadr3.yaml`.

## 1. Version declaration

The spec declares `openapi: 3.0.0` but the content is compatible with 3.1.0. Legba validates the version string against the pattern `^3\.1\.\d+(-.+)?$` and rejects anything else.

```diff
-openapi: 3.0.0
+openapi: 3.1.0
```

## 2. `exclusiveMaximum` syntax

OpenAPI 3.0.x uses `exclusiveMaximum: true` (boolean) alongside a separate `maximum` value. OpenAPI 3.1.x (which aligns with JSON Schema draft 2020-12) uses `exclusiveMaximum` as the numeric value directly.

This affects the `problem` schema's `status` field (line ~2626):

```diff
           minimum: 100
-          maximum: 600
-          exclusiveMaximum: true
+          exclusiveMaximum: 600
```

## Applying the patches

When updating `resources/openadr3.yaml` from a new spec release:

```bash
cp ../openadr3-specification/3.1.0/openadr3.yaml resources/openadr3.yaml
sed -i '' 's/^openapi: 3.0.0/openapi: 3.1.0/' resources/openadr3.yaml
sed -i '' '/maximum: 600/{N;s/maximum: 600\n          exclusiveMaximum: true/exclusiveMaximum: 600/;}' resources/openadr3.yaml
```

## Why not fix upstream?

The upstream spec (`openadr3-specification`) targets broad compatibility and many tools expect OpenAPI 3.0.x. Changing it to 3.1.0 would break those consumers. These patches are local to the VTN server's copy of the spec.
