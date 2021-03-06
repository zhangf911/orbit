/*
 Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1.  Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2.  Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
 EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.ea.orbit.samples.memoize;

import com.ea.orbit.actors.IAddressable;
import com.ea.orbit.actors.providers.IInvokeHookProvider;
import com.ea.orbit.actors.runtime.IRuntime;
import com.ea.orbit.concurrent.Task;

import net.jodah.expiringmap.ExpiringMap;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MemoizeHookProvider implements IInvokeHookProvider
{

    ExpiringMap<String, Task> memoizeMap = ExpiringMap.builder().variableExpiration().build();

    @Override
    public Task invoke(IRuntime runtime, IAddressable toReference, Method m, boolean oneWay, int methodId, Object[] params)
    {
        Memoize memoizeAnnotation = m.getAnnotation(Memoize.class);
        if (memoizeAnnotation != null)
        {
            long memoizeMaxMillis = memoizeAnnotation.unit().toMillis(memoizeAnnotation.time());
            String key = Integer.toString(methodId) + "_" + Stream.of(params).map(p -> Integer.toString(p.hashCode())).collect(Collectors.joining("_"));
            Task cached = memoizeMap.get(key);
            if (cached == null)
            {
                cached = runtime.sendMessage(toReference, oneWay, methodId, params);
                memoizeMap.put(key, cached, ExpiringMap.ExpirationPolicy.CREATED, memoizeMaxMillis, TimeUnit.MILLISECONDS);
            }
            return cached;
        }
        return runtime.sendMessage(toReference, oneWay, methodId, params);
    }

}
