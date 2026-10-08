package com.example.shutdownprotection

import android.app.Application

/** Accessibility-only startup: no managed container, receivers or device policy wiring. */
class NoResetApplication : Application()
