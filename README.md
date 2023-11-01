# Kotlin/Native & Rust interoperability

## Main goal

We want to build an executable in Kotlin to use the language capabilities and our knowledge, but at
the same time deliver fully independent of JVM solution, which can be as small as few megabytes.

However, not every functionality can be easily handled in Kotlin, so sometimes it's just more convenient to
prepare some external library, expose its symbols with C ABI and call them in expected places in Kotlin.
We would prefer to build a static library, which can then be statically linked to the final executable,
to make sure that the end user needs only a single binary to run our program.

In our specific example, we see how to write a CLI tool in Kotlin/Native, but prepare an external library
in Rust. The external library is responsible for unzipping the given file to a specific location. In Kotlin,
we handle the rest of business logic, which (for the sake of simplicity) is just proper
handling of program arguments and deleting some files, to see how the Kotlin/Native libraries can be used.

## Project configuration

Let's start with having look at the project structure that's worth explaining what files
and directories are responsible for which part of the project configuration.

```
├── build.gradle.kts
├── gradle
│   └── wrapper
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradle.properties
├── gradlew
├── gradlew.bat
├── README.md
├── rust_lib
│   ├── build.rs
│   ├── Cargo.toml
│   └── src
│       └── lib.rs
├── settings.gradle.kts
└── src
    ├── nativeInterop
    │   └── cinterop
    │       └── librust_lib.def
    └── nativeMain
        └── kotlin
            ├── Main.kt
            └── ReportedError.kt
```

### Rust library

First of all, we include the [rust_lib](./rust_lib) directory in our root. It contains the Rust project exporting static
library.
Its [Cargo.ml](./rust_lib/Cargo.toml) explicitly says that the build result is `staticlib`, for which release profile
has multiple final binary size oriented optimizations enabled. It also declares two dependencies:

- `zip` being our business-specific dependency that simplifies the implementation of unzipping files
- `cbindgen` being must-have dependency, which is responsible for exporting the `.h` header file based on the
  definitions from our library. You
  can find a proper file [rust_lib.h](./rust_lib/target/rust_lib.h) after executing `buildRustLib` Gradle task.
  Additionally, in [build.rs](./rust_lib/build.rs) we include actual logics responsible for this process.

The [lib.rs](./rust_lib/src/lib.rs) file contains, on the other hand, the actual definition of our
exported library. We need to specify all the functions' symbols as `pub extern "C"` and add the
`#[no_mangle]` macro to make them accessible via C ABI, as well as it's crucial to use a proper type
for function arguments and returned value – they need to be compatible with the ones that C language would
produce.

That implies the proper conversion of arguments, to make them friendly to Rust. In our case we work
with string values, which are passed as `char *out_path`. It's important to use `unsafe { CStr::from_ptr(chars) };`
to convert them to `&str` – notice that using `unsafe { CString::from_raw(chars) };` is an incorrect approach as
it leads to invalid free operation (we can find in `CString::from_raw` documentation that
`If you need to borrow a string that was allocated by foreign code, use CStr.`)

The final static library file, produced from our `rust_lib`, will be available in [release](./rust_lib/target/release)
directory, and we're going to use it while compiling final binary, to find the symbols defined in
header file.

### Gradle project

We configure our root project with Gradle, using Kotlin Multiplatform Plugin to enable compilation to native
targets. The main configuration file [build.gradle.kts](./build.gradle.kts) has a few, quite interesting definitions,
that we've used to achieve our goal of building independent binary.

We use `DefaultNativePlatform` helper to read current host OS and architecture and configure the
compilation for our platform. Inside the `kotlin { ... }` block we configure the native target to
`host` and then configure it inside the `target { ... }` block. There are two parts of the configuration that
play the main role in our final result.

The first part

```kotlin
compilations.getByName("main").cinterops {
    create("librust_lib") {
        val buildRustLib by tasks.creating {
            exec {
                executable = cargoAbsolutePath
                args(
                    "build",
                    "--manifest-path", projectFile("rust_lib/Cargo.toml"),
                    "--package", "rust_lib",
                    "--lib",
                    "--release"
                )
            }
        }
        tasks.getByName(interopProcessingTaskName) {
            dependsOn(buildRustLib)
        }
        header(projectFile("rust_lib/target/rust_lib.h"))
    }
}
```

is responsible for interoperability between Kotlin and C symbols. We create the `librust_lib` cinterop
and configure the header location manually with `projectFile` function to get absolute path of the header,
having the current location of project directory. Moreover, we add extra task named
`buildRustLib`, which calls `cargo` command to build our Rust library before the cinterop task is executed.
To make sure we have our header file available, we explicitly define the dependency on `interopProcessingTaskName`.
It's worth mentioning here, that we include empty [librust_lib.def](./src/nativeInterop/cinterop/librust_lib.def) file
in our project. It's required by project structure, as described in
the [official documentation example](https://kotlinlang.org/docs/native-app-with-c-and-libcurl.html#add-interoperability-to-the-build-process).
However, we want to define the required `header` relatively to project directory, and it seems that
working and nice approach is to configure it directly in our build script.

The second step — configuring final executable with

```kotlin
binaries.executable {
    entryPoint = "main"
    baseName = "kotlin-tool"
    linkerOpts += rustLibAbsolutePath
}
```

is essential to link our static library to the final compilation result from Kotlin. The value of `rustLibAbsolutePath`
depends on current OS, as different systems support different types of static libraries.

Additionally, we show how to add Kotlin/Native dependencies to some external libraries
with source set dependencies as

```kotlin
sourceSets {
    getByName("nativeMain").dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.0")
    }
}
```

One last thing is including the definition of extra task named `binaries` to commonize
the building process on all platforms. It calls the platform-specific task
that builds the release and debug binaries for host architecture.

## Compilation

We can easily compile the final binary by calling gradle task

```shell
./gradlew binaries
```

which produces `kotlin-tool` binary in a proper subdirectory of [bin](./build/bin/) build results.

We can use it to unzip some zip file, just by passing our file's path as program argument.

## Conclusion

Configuring the Kotlin/Native project in a basic scenario might not be so straightforward
if we want to refer to some libraries built as a part of our project. Thanks to Gradle
flexibility we can call `cargo`, build our Rust dependency and configure all the files
relatively to our root project. In these few steps we get some reference project configuration
that should work in most case and make our life simpler when we decide to build native binaries
with Kotlin and glue them with some external Rust libraries.
