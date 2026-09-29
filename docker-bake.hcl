group "default" {
  targets = [
    "gateway",
    "ad",
    "ai",
    "customer",
    "finance",
    "logistics",
    "message",
    "multiplatform",
    "ops",
    "order",
    "procurement",
    "product",
    "report",
    "search",
    "spapi",
    "user",
    "frontend"
  ]
}
variable "REGISTRY" {
  default = "ghcr.io/cgs123456"
}

variable "TAG" {
  default = "dev"
}

variable "GIT_SHA" {
  default = "unknown"
}

variable "BUILD_DATE" {
  default = "unknown"
}

target "common" {
  context   = "."
  dockerfile = "Dockerfile"
  # amd64-only for now: each target runs a full in-container Maven build, and
  # arm64 under QEMU made the 17-target release exceed practical runner limits.
  # Re-enable linux/arm64 after moving the jar build out of the image.
  platforms = ["linux/amd64"]
  labels = {
    "org.opencontainers.image.source"   = "https://github.com/cgs123456/AmazonERP"
    "org.opencontainers.image.version"  = "${TAG}"
    "org.opencontainers.image.revision" = "${GIT_SHA}"
    "org.opencontainers.image.created"  = "${BUILD_DATE}"
  }
}

target "gateway" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-gateway:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-gateway"
    PORT      = "10010"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "ad" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-ad:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-ad"
    PORT      = "8097"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "ai" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-ai:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-ai"
    PORT      = "8091"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "customer" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-customer:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-customer"
    PORT      = "8099"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "finance" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-finance:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-finance"
    PORT      = "8103"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "logistics" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-logistics:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-logistics"
    PORT      = "8100"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "message" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-message:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-message"
    PORT      = "8889"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "multiplatform" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-multiplatform:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-multiplatform"
    PORT      = "8104"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "ops" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-ops:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-ops"
    PORT      = "8101"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "order" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-order:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-order"
    PORT      = "8105"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "procurement" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-procurement:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-procurement"
    PORT      = "8098"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "product" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-product:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-product"
    PORT      = "8095"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "report" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-report:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-report"
    PORT      = "8102"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "search" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-search:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-search"
    PORT      = "8090"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "spapi" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-spapi:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-spapi"
    PORT      = "8096"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "user" {
  inherits   = ["common"]
  dockerfile = "Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-user:${TAG}-${GIT_SHA}"]
  args = {
    MODULE    = "amz-service/amz-service-user"
    PORT      = "8080"
    VERSION   = "${TAG}"
    VCS_REF   = "${GIT_SHA}"
    BUILD_DATE = "${BUILD_DATE}"
  }
}

target "frontend" {
  inherits   = ["common"]
  dockerfile = "amz-frontend/Dockerfile"
  tags       = ["${REGISTRY}/amazonerp-frontend:${TAG}-${GIT_SHA}"]
  args = {
    VERSION            = "${TAG}"
    VCS_REF            = "${GIT_SHA}"
    BUILD_DATE         = "${BUILD_DATE}"
    VITE_API_BASE_URL  = "/api"
    VITE_WS_URL        = "/ws/socket"
  }
}