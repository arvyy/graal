/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.os;

import static com.oracle.svm.core.Isolates.IMAGE_HEAP_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_END;
import static com.oracle.svm.core.util.PointerUtils.roundUp;

import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.c.function.CEntryPointErrors;
import com.oracle.svm.core.code.DynamicMethodAddressResolutionHeapSupport;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.os.VirtualMemoryProvider.Access;
import com.oracle.svm.core.util.UnsignedUtils;

import jdk.graal.compiler.word.Word;

public abstract class AbstractCopyingImageHeapProvider extends AbstractImageHeapProvider {
    @Override
    public boolean guaranteesHeapPreferredAddressSpaceAlignment() {
        return true;
    }

    @Override
    @Uninterruptible(reason = "Called during isolate initialization.")
    public int initialize(Pointer reservedAddressSpace, UnsignedWord reservedSize, WordPointer basePointer, WordPointer endPointer) {
        boolean haveDynamicMethodResolution = DynamicMethodAddressResolutionHeapSupport.isEnabled();
        UnsignedWord preHeapRequiredBytes = WordFactory.zero();
        UnsignedWord alignment = WordFactory.unsigned(Heap.getHeap().getPreferredAddressSpaceAlignment());

        if (haveDynamicMethodResolution) {
            int res = DynamicMethodAddressResolutionHeapSupport.get().initialize();
            if (res != CEntryPointErrors.NO_ERROR) {
                return res;
            }
            preHeapRequiredBytes = DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes();
        }

        // Reserve an address space for the image heap if necessary.
        UnsignedWord imageHeapAddressSpaceSize = getImageHeapAddressSpaceSize();
        UnsignedWord totalAddressSpaceSize = imageHeapAddressSpaceSize.add(preHeapRequiredBytes);

        Pointer heapBase;
        Pointer allocatedMemory = WordFactory.nullPointer();
        if (reservedAddressSpace.isNull()) {
            heapBase = allocatedMemory = VirtualMemoryProvider.get().reserve(totalAddressSpaceSize, alignment, false);
            if (allocatedMemory.isNull()) {
                return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED;
            }
        } else {
            if (reservedSize.belowThan(totalAddressSpaceSize)) {
                return CEntryPointErrors.INSUFFICIENT_ADDRESS_SPACE;
            }
            heapBase = reservedAddressSpace;
        }

        if (haveDynamicMethodResolution) {
            heapBase = heapBase.add(preHeapRequiredBytes);
            if (allocatedMemory.isNonNull()) {
                allocatedMemory.add(preHeapRequiredBytes);
            }
            Pointer installOffset = heapBase.subtract(DynamicMethodAddressResolutionHeapSupport.get().getRequiredPreHeapMemoryInBytes());
            int error = DynamicMethodAddressResolutionHeapSupport.get().install(installOffset);

            if (error != CEntryPointErrors.NO_ERROR) {
                freeImageHeap(allocatedMemory);
                return error;
            }
        }

        // Copy the memory to the reserved address space.
        Word imageHeapBegin = IMAGE_HEAP_BEGIN.get();
        UnsignedWord imageHeapSizeInFile = getImageHeapSizeInFile();
        Pointer imageHeap = heapBase.add(Heap.getHeap().getImageHeapOffsetInAddressSpace());
        int result = commitAndCopyMemory(imageHeapBegin, imageHeapSizeInFile, imageHeap);
        if (result != CEntryPointErrors.NO_ERROR) {
            freeImageHeap(allocatedMemory);
            return result;
        }

        // Protect the read-only parts at the start of the image heap.
        UnsignedWord pageSize = VirtualMemoryProvider.get().getGranularity();
        Pointer firstPartOfReadOnlyImageHeap = imageHeap;
        UnsignedWord writableBeginPageOffset = UnsignedUtils.roundDown(IMAGE_HEAP_WRITABLE_BEGIN.get().subtract(imageHeapBegin), pageSize);
        if (writableBeginPageOffset.aboveThan(0)) {
            if (VirtualMemoryProvider.get().protect(firstPartOfReadOnlyImageHeap, writableBeginPageOffset, Access.READ) != 0) {
                freeImageHeap(allocatedMemory);
                return CEntryPointErrors.PROTECT_HEAP_FAILED;
            }
        }

        // Protect the read-only parts at the end of the image heap.
        UnsignedWord writableEndPageOffset = UnsignedUtils.roundUp(IMAGE_HEAP_WRITABLE_END.get().subtract(imageHeapBegin), pageSize);
        if (writableEndPageOffset.belowThan(imageHeapSizeInFile)) {
            Pointer afterWritableBoundary = imageHeap.add(writableEndPageOffset);
            UnsignedWord afterWritableSize = imageHeapSizeInFile.subtract(writableEndPageOffset);
            if (VirtualMemoryProvider.get().protect(afterWritableBoundary, afterWritableSize, Access.READ) != 0) {
                freeImageHeap(allocatedMemory);
                return CEntryPointErrors.PROTECT_HEAP_FAILED;
            }
        }

        // Update the heap base and end pointers.
        basePointer.write(heapBase);
        if (endPointer.isNonNull()) {
            endPointer.write(roundUp(imageHeap.add(imageHeapSizeInFile), pageSize));
        }
        return CEntryPointErrors.NO_ERROR;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    protected int commitAndCopyMemory(Pointer loadedImageHeap, UnsignedWord imageHeapSize, Pointer newImageHeap) {
        Pointer actualNewImageHeap = VirtualMemoryProvider.get().commit(newImageHeap, imageHeapSize, Access.READ | Access.WRITE);
        if (actualNewImageHeap.isNull() || actualNewImageHeap.notEqual(newImageHeap)) {
            return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED;
        }
        return copyMemory(loadedImageHeap, imageHeapSize, newImageHeap);
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    protected abstract int copyMemory(Pointer loadedImageHeap, UnsignedWord imageHeapSize, Pointer newImageHeap);

    @Override
    @Uninterruptible(reason = "Called during isolate tear-down.")
    public int freeImageHeap(PointerBase heapBase) {
        if (heapBase.isNonNull()) {
            UnsignedWord totalAddressSpaceSize = getImageHeapAddressSpaceSize();
            Pointer addressSpaceStart = (Pointer) heapBase;
            if (DynamicMethodAddressResolutionHeapSupport.isEnabled()) {
                UnsignedWord preHeapRequiredBytes = DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes();
                totalAddressSpaceSize = totalAddressSpaceSize.add(preHeapRequiredBytes);
                addressSpaceStart = addressSpaceStart.subtract(preHeapRequiredBytes);
            }

            if (VirtualMemoryProvider.get().free(addressSpaceStart, totalAddressSpaceSize) != 0) {
                return CEntryPointErrors.FREE_IMAGE_HEAP_FAILED;
            }
        }
        return CEntryPointErrors.NO_ERROR;
    }
}
