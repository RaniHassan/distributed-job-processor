package com.example.jobprocessor.security.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.jobprocessor.job.service.JobManagementService;
import com.example.jobprocessor.user.domain.Role;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class RolePermissionsTest {

    @Test
    void userHasJobPermissions() {
        assertThat(RolePermissions.of(Role.USER))
                .containsExactlyInAnyOrder(Permission.JOB_CREATE, Permission.JOB_READ, Permission.JOB_CANCEL);
    }

    @Test
    void adminHasTheSameJobPermissions() {
        assertThat(RolePermissions.of(Role.ADMIN)).isEqualTo(RolePermissions.of(Role.USER));
    }

    @Test
    void noGenericUpdatePermissionExists() {
        assertThat(Arrays.stream(Permission.values()).map(Enum::name)).doesNotContain("JOB_UPDATE");
    }

    @Test
    void returnedSetCannotBeModified() {
        assertThatCode(() -> RolePermissions.of(Role.USER).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** Every public Job Management operation requires a real permission name. */
    @Test
    void jobManagementOperationsRequireDefinedPermissions() {
        Pattern authority = Pattern.compile("hasAuthority\\('([A-Z_]+)'\\)");
        Method[] operations = Arrays.stream(JobManagementService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .toArray(Method[]::new);

        assertThat(operations).isNotEmpty();
        for (Method operation : operations) {
            PreAuthorize annotation = operation.getAnnotation(PreAuthorize.class);
            assertThat(annotation).as(operation.getName() + " must be protected").isNotNull();
            Matcher matcher = authority.matcher(annotation.value());
            assertThat(matcher.matches()).as(annotation.value()).isTrue();
            assertThatCode(() -> Permission.valueOf(matcher.group(1))).doesNotThrowAnyException();
        }
    }
}
