## 1. Size check

- [x] 1.1 `Proxy.handleRequest`: skip the `resources.maxSize` check for `POST /v1/admin/system-import`
- [x] 1.2 `ProxyTest.testHandle_SystemImportSkipsBodySizeLimit`: a 1 GiB `application/zip` body to the import route is not
  answered `413` and goes on to authorization; scenario "Large system import archive"
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*ProxyTest"`

## 2. Docs

- [x] 2.1 `docs/error_codes.md`: the `413` section names the exception
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate exempt-system-import-body-limit --strict`
