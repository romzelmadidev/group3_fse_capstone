import 'package:flutter/material.dart';

final GlobalKey<NavigatorState> rootNavigatorKey = GlobalKey<NavigatorState>();

/// Route argument passed to `/login` when a session ended because it expired.
const sessionExpiredArg = 'session_expired';
