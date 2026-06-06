@echo off
echo Preparing files for packaging...
call gradlew.bat installDist

echo Cleaning up existing package directory...
if exist "build\jpackage\GiveMeTheTreasure" (
    rmdir /s /q "build\jpackage\GiveMeTheTreasure"
)

echo Starting jpackage...
"C:\Users\zihasoo\.jdks\jdk-25\bin\jpackage.exe" --type app-image --name GiveMeTheTreasure --input build\install\OOP\lib --main-jar OOP-1.0.jar --main-class com.oop.payday.Main --dest build\jpackage --java-options "--enable-native-access=javafx.graphics"

echo Packaging complete! Check the build\jpackage\GiveMeTheTreasure folder.