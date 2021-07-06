/*
 * MIT License
 *
 * Copyright (c) 2021 tianwailaike61
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.twlk.lib_lint_client_v3_3;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.builder.model.Variant;
import com.android.sdklib.BuildToolInfo;
import com.android.tools.lint.LintCliFlags;
import com.android.tools.lint.Warning;
import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.LintBaseline;
import com.android.tools.lint.client.api.LintRequest;
import com.android.tools.lint.detector.api.Context;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.LintFix;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.TextFormat;
import com.android.tools.lint.gradle.LintGradleClient;
import com.android.tools.lint.gradle.api.VariantInputs;
import com.android.utils.Pair;
import com.twlk.lib_lint_base.LintResultCollector;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class IncrementLintClient extends LintGradleClient {

    private LintResultCollector collector;
    private final IssueRegistry registry;

    public IncrementLintClient(@NonNull String version,
                               @NonNull IssueRegistry registry,
                               @NonNull LintCliFlags flags,
                               @NonNull org.gradle.api.Project gradleProject,
                               @Nullable File sdkHome,
                               @Nullable Variant variant,
                               @NonNull VariantInputs variantInputs,
                               @Nullable BuildToolInfo buildToolInfo,
                               boolean isAndroid,
                               String baselineVariantName) {
        super(version, registry, flags, gradleProject, sdkHome, variant, variantInputs, buildToolInfo,
                isAndroid, baselineVariantName);
        this.registry = registry;
    }

    @Override
    protected LintRequest createLintRequest(List<File> files) {
        LintRequest request = super.createLintRequest(files);
        for (com.android.tools.lint.detector.api.Project project : request.getProjects()) {
            for (File f : collector.getFiles()) {
                project.addFile(f);
            }
        }
        return request;
    }

    public Pair<List<Warning>, LintBaseline> run(LintResultCollector collector) throws IOException {
        this.collector = collector;
        return super.run(this.registry);
    }

    @Override
    public void report(Context context, Issue issue, Severity severity, Location location, String message, TextFormat format, LintFix fix) {
        if (collector.addReport(issue.getId(), severity.isError(), context.file)) {
            super.report(context, issue, severity, location, message, format, fix);
        }
    }
}
