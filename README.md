# captcha-service
Includes Text, Image, Slider, Grid, and Audio captchas as APIs for Generation and Validation

Overview:
  This project is a complete CAPTCHA generation service built using Spring Boot and Java 17. It provides multiple human verification challenges such as text-based   captchas, image puzzles, sliders, grid captchas, and audio-based captchas. The system is designed to be flexible, production-ready, and easily integrable into     any authentication workflow.

Features:

Text-Image CAPTCHA
  Generates random alphanumeric or word-based text images.
  Supports noise, distortion, gradients, and variable fonts.
  Simple verification via unique ID and token.

Slider CAPTCHA
  Drag-and-drop puzzle challenge with dynamic shapes and random background images.
  Smooth image rendering using Java AWT and Graphics2D.
  Randomized positions and shapes for every request.

Grid CAPTCHA
  Displays a 3x3 or 4x4 grid of images.
  The user must select all images that match a given object prompt (for example: “Select all images with trees”).
  Ideal for advanced human verification systems.

Audio CAPTCHA
  Generates .wav audio files containing random numbers.
  Helpful for visually impaired users.
  Supports digits (0–9).

Centralized Service
  Each challenge has a unique ID and is stored temporarily in-memory.
  Responses are Base64-encoded for easy use in frontend applications.
  Can be extended to use Redis or a database for persistence.
  Works seamlessly with any frontend framework such as React, Vue, Angular, or plain JavaScript.

Tech Stack:
  Spring Boot 3.x
  Java 17

Java AWT and Graphics2D for image generation

javax.sound.sampled for WAV audio generation
