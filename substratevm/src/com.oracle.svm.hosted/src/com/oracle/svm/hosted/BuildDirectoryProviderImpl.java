/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted;

import java.nio.file.Path;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.BuildDirectoryProvider;

@Platforms(Platform.HOSTED_ONLY.class)
public class BuildDirectoryProviderImpl implements BuildDirectoryProvider {

    @Override
    public Path getHome() {
        String home = System.getProperty("native-image.root");
        if (home == null || home.isEmpty()) {
            // for compatibility with old HomeFinder -> should be removed
            home = System.getProperty("org.graalvm.launcher.home");
        }
        if (home == null || home.isEmpty()) {
            // for compatibility with old HomeFinder -> should be removed
            home = System.getProperty("org.graalvm.launcher.relative.home");
        }
        if (home == null || home.isEmpty()) {
            home = System.getProperty("java.home");
        }
        return Path.of(home);
    }

    static void install() {
        ImageSingletons.add(BuildDirectoryProvider.class, new BuildDirectoryProviderImpl());
    }

}
